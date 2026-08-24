package com.kmultan.claims.application;

import com.kmultan.claims.domain.ClaimStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business-level counters for the Grafana dashboard: what the platform is doing, not just how the JVM feels. */
@Component
public class ClaimMetrics {

    private final MeterRegistry registry;

    public ClaimMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void submitted() {
        Counter.builder("claims.submitted").description("Claims submitted").register(registry).increment();
    }

    public void transitioned(ClaimStatus to) {
        Counter.builder("claims.transitions").description("Claim status transitions")
                .tag("to", to.name()).register(registry).increment();
    }
}
