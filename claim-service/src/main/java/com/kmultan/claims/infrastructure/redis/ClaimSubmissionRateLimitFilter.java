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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Fixed-window rate limit on claim submission, per client, shared across
 * service instances via Redis ({@code INCR} + {@code EXPIRE}). Protects the
 * outbox/Kafka path and the ML service from a runaway client; the window is
 * deliberately simple — a token bucket (Bucket4j) would smooth bursts better.
 */
@Component
@Order(1)
public class SubmitRateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final int perMinute;
    private final Clock clock;

    public SubmitRateLimitFilter(StringRedisTemplate redis, Clock clock, @Value("${claims.ratelimit.submit-per-minute}") int perMinute) {
        this.redis = redis;
        this.clock = clock;
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && "/api/v1/claims".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getHeader("X-Client-Id");
        if (client == null || client.isBlank()) {
            client = request.getRemoteAddr();
        }
        long nowSec = clock.instant().getEpochSecond();
        long window = nowSec / 60;
        String key = "ratelimit:submit:" + client + ":" + window;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(120));
        }
        long remaining = Math.max(0, perMinute - (count == null ? 0 : count));
        response.setHeader("X-RateLimit-Limit", Integer.toString(perMinute));
        response.setHeader("X-RateLimit-Remaining", Long.toString(remaining));
        if (count != null && count > perMinute) {
            long retryAfter = 60 - (nowSec % 60);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            IdempotencyKeyFilter.problem(response, HttpStatus.TOO_MANY_REQUESTS, "Too many submissions",
                    "Limit is " + perMinute + " claim submissions per minute per client");
            return;
        }
        chain.doFilter(request, response);
    }
}
