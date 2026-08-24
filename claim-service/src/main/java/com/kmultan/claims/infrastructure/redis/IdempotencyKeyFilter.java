package com.kmultan.claims.infrastructure.redis;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Client-supplied {@code Idempotency-Key} on claim submission. A mobile client
 * that times out and retries must not create two claims. Redis holds
 * key → claim id (24h); a concurrent duplicate sees the in-progress marker and
 * gets 409 instead of racing.
 *
 * Why Redis and not Postgres: the check happens before the transaction and
 * must be cheap and expiring; Redis {@code SET NX EX} is exactly that primitive.
 */
@Component
@Order(2)
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotencyKeyFilter(StringRedisTemplate redis, @Value("${claims.idempotency.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && "/api/v1/claims".equals(request.getRequestURI()))
                || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (!KEY.matcher(key).matches()) {
            problem(response, HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key", "8-128 chars: letters, digits, '-' or '_'");
            return;
        }
        String redisKey = "idem:claim:" + key;
        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(acquired)) {
            String existing = redis.opsForValue().get(redisKey);
            if (existing == null || IN_PROGRESS.equals(existing)) {
                problem(response, HttpStatus.CONFLICT, "Request in progress", "A request with this Idempotency-Key is still being processed");
            } else {
                // replay: same key, already created -> point at the existing claim, no second submission
                response.setStatus(HttpStatus.OK.value());
                response.setHeader("Location", "/api/v1/claims/" + existing);
                response.setHeader("Idempotent-Replayed", "true");
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"id\":\"" + existing + "\",\"replayed\":true}");
            }
            return;
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
            if (wrapped.getStatus() == HttpStatus.CREATED.value()) {
                String location = wrapped.getHeader("Location");
                String claimId = location.substring(location.lastIndexOf('/') + 1);
                redis.opsForValue().set(redisKey, claimId, ttl);
            } else {
                redis.delete(redisKey);   // failed submission: let the client retry with the same key
            }
        } catch (RuntimeException | ServletException | IOException e) {
            redis.delete(redisKey);
            throw e;
        } finally {
            wrapped.copyBodyToResponse();
        }
    }

    static void problem(HttpServletResponse response, HttpStatus status, String title, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}"
                .formatted(title, status.value(), detail));
    }
}
