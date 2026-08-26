package com.kmultan.claims.api.admin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Technical usage for the admin dashboard, read from this instance's meter
 * registry (the same numbers Prometheus scrapes) plus the live rate-limit
 * counters in Redis. Grafana remains the place for history; this is "now".
 */
@RestController
@RequestMapping("/api/v1/admin/usage")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUsageController {

    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate redis;

    public AdminUsageController(MeterRegistry meterRegistry, StringRedisTemplate redis) {
        this.meterRegistry = meterRegistry;
        this.redis = redis;
    }

    public record EndpointUsage(
            String method, String uri, long requests, double averageMillis, double maxMillis, long errors) {}

    public record ClientUsage(String clientId, long submissionsThisMinute) {}

    public record Usage(
            long uptimeSeconds,
            double cpuUsage,
            long heapUsedBytes,
            long heapMaxBytes,
            long totalHttpRequests,
            List<EndpointUsage> endpoints,
            double claimsSubmitted,
            double outboxPublished,
            double outboxPending,
            Map<String, Double> claimTransitions,
            List<ClientUsage> topClients) {}

    @GetMapping
    public Usage usage() {
        Map<String, EndpointUsage> endpoints = new LinkedHashMap<>();
        long totalRequests = 0;
        for (Timer timer : meterRegistry.find("http.server.requests").timers()) {
            String uri = timer.getId().getTag("uri");
            String method = timer.getId().getTag("method");
            if (uri == null || uri.startsWith("/actuator") || "UNKNOWN".equals(uri)) {
                continue;
            }
            String key = method + " " + uri;
            long count = timer.count();
            totalRequests += count;
            boolean error = timer.getId().getTag("status") != null
                    && timer.getId().getTag("status").startsWith("5");
            EndpointUsage existing = endpoints.get(key);
            long requests = (existing == null ? 0 : existing.requests()) + count;
            double average = timer.mean(TimeUnit.MILLISECONDS);
            double max = Math.max(existing == null ? 0 : existing.maxMillis(), timer.max(TimeUnit.MILLISECONDS));
            long errors = (existing == null ? 0 : existing.errors()) + (error ? count : 0);
            endpoints.put(key, new EndpointUsage(method, uri, requests, average, max, errors));
        }
        List<EndpointUsage> sortedEndpoints = endpoints.values().stream()
                .sorted((left, right) -> Long.compare(right.requests(), left.requests()))
                .toList();

        Map<String, Double> transitions = new LinkedHashMap<>();
        for (Counter counter : meterRegistry.find("claims.transitions").counters()) {
            transitions.put(counter.getId().getTag("to"), counter.count());
        }

        return new Usage(
                (long) gauge("process.uptime"),
                gauge("system.cpu.usage"),
                (long) sumGauges("jvm.memory.used", "area", "heap"),
                (long) sumGauges("jvm.memory.max", "area", "heap"),
                totalRequests,
                sortedEndpoints,
                counter("claims.submitted"),
                counter("outbox.published"),
                gauge("outbox.pending"),
                transitions,
                topClientsThisMinute());
    }

    private double gauge(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private double sumGauges(String name, String tagKey, String tagValue) {
        return meterRegistry.find(name).tag(tagKey, tagValue).gauges().stream()
                .mapToDouble(Gauge::value)
                .sum();
    }

    private double counter(String name) {
        return meterRegistry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private List<ClientUsage> topClientsThisMinute() {
        Set<String> keys = redis.keys("ratelimit:submit:*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        long currentWindow = Duration.ofMillis(System.currentTimeMillis()).toSeconds() / 60;
        return keys.stream()
                .filter(key -> key.endsWith(":" + currentWindow))
                .map(key -> {
                    String value = redis.opsForValue().get(key);
                    String clientId = key.substring("ratelimit:submit:".length(), key.lastIndexOf(':'));
                    return new ClientUsage(clientId, value == null ? 0 : Long.parseLong(value));
                })
                .sorted((left, right) -> Long.compare(right.submissionsThisMinute(), left.submissionsThisMinute()))
                .limit(10)
                .toList();
    }
}
