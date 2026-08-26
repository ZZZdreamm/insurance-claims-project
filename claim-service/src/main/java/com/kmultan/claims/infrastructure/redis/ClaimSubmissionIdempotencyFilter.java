package com.kmultan.claims.infrastructure.redis;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.web.ProblemDetails;

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
public class ClaimSubmissionIdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");
    private static final String IN_PROGRESS_MARKER = "IN_PROGRESS";
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration keyTtl;

    public ClaimSubmissionIdempotencyFilter(
            StringRedisTemplate redis, ObjectMapper objectMapper, @Value("${claims.idempotency.ttl}") Duration keyTtl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyTtl = keyTtl;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean isSubmit = HttpMethod.POST.matches(request.getMethod())
                && ClaimSubmissionRateLimitFilter.SUBMIT_PATH.equals(request.getRequestURI());
        return !isSubmit || request.getHeader(IDEMPOTENCY_KEY_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (!KEY_FORMAT.matcher(idempotencyKey).matches()) {
            ProblemDetails.write(
                    response,
                    objectMapper,
                    HttpStatus.BAD_REQUEST,
                    "Invalid Idempotency-Key",
                    "8-128 chars: letters, digits, '-' or '_'");
            return;
        }
        String redisKey = "idem:claim:" + idempotencyKey;
        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, IN_PROGRESS_MARKER, IN_PROGRESS_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            replayOrReject(response, redisKey);
            return;
        }
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, wrappedResponse);
            if (wrappedResponse.getStatus() == HttpStatus.CREATED.value()) {
                String location = wrappedResponse.getHeader("Location");
                String claimId = location.substring(location.lastIndexOf('/') + 1);
                redis.opsForValue().set(redisKey, claimId, keyTtl);
            } else {
                redis.delete(redisKey); // failed submission: let the client retry with the same key
            }
        } catch (RuntimeException | ServletException | IOException exception) {
            redis.delete(redisKey);
            throw exception;
        } finally {
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void replayOrReject(HttpServletResponse response, String redisKey) throws IOException {
        String stored = redis.opsForValue().get(redisKey);
        if (stored == null || IN_PROGRESS_MARKER.equals(stored)) {
            ProblemDetails.write(
                    response,
                    objectMapper,
                    HttpStatus.CONFLICT,
                    "Request in progress",
                    "A request with this Idempotency-Key is still being processed");
            return;
        }
        // replay: same key, already created -> point at the existing claim, no second submission
        response.setStatus(HttpStatus.OK.value());
        response.setHeader("Location", ClaimSubmissionRateLimitFilter.SUBMIT_PATH + "/" + stored);
        response.setHeader(REPLAYED_HEADER, "true");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"id\":\"" + stored + "\",\"replayed\":true}");
    }
}
