package com.kmultan.claims.infrastructure.redis;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.platform.web.ProblemDetails;

/**
 * Fixed-window rate limit on claim submission, per client, shared across
 * service instances via Redis ({@code INCR} + {@code EXPIRE}). Protects the
 * outbox/Kafka path and the ML service from a runaway client; the window is
 * deliberately simple — a token bucket (Bucket4j) would smooth bursts better.
 */
@Component
@Order(1)
public class ClaimSubmissionRateLimitFilter extends OncePerRequestFilter {

    public static final String CLIENT_ID_HEADER = "X-Client-Id";
    static final String SUBMIT_PATH = "/api/v1/claims";
    private static final long WINDOW_SECONDS = 60L;
    private static final Duration KEY_TTL = Duration.ofSeconds(2 * WINDOW_SECONDS);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final int limitPerMinute;

    public ClaimSubmissionRateLimitFilter(
            StringRedisTemplate redis,
            Clock clock,
            ObjectMapper objectMapper,
            @Value("${claims.ratelimit.submit-per-minute}") int limitPerMinute) {
        this.redis = redis;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && SUBMIT_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientId = request.getHeader(CLIENT_ID_HEADER);
        if (clientId == null || clientId.isBlank()) {
            clientId = request.getRemoteAddr();
        }
        long nowSeconds = clock.instant().getEpochSecond();
        String windowKey = "ratelimit:submit:" + clientId + ":" + (nowSeconds / WINDOW_SECONDS);

        Long requestsInWindow = redis.opsForValue().increment(windowKey);
        long count = requestsInWindow == null ? 0 : requestsInWindow;
        if (count == 1) {
            redis.expire(windowKey, KEY_TTL);
        }
        response.setHeader("X-RateLimit-Limit", Integer.toString(limitPerMinute));
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0, limitPerMinute - count)));
        if (count > limitPerMinute) {
            response.setHeader("Retry-After", Long.toString(WINDOW_SECONDS - (nowSeconds % WINDOW_SECONDS)));
            ProblemDetails.write(
                    response,
                    objectMapper,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many submissions",
                    "Limit is " + limitPerMinute + " claim submissions per minute per client");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
