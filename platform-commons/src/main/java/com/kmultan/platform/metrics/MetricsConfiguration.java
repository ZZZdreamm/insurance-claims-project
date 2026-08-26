package com.kmultan.platform.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

/**
 * Histogram buckets for the timers the Grafana p95 panels query. Done in code
 * rather than YAML so it applies identically in every service and cannot be
 * lost to a property-binding subtlety with dotted meter names.
 */
@Configuration
public class MetricsConfiguration {

    private static final String[] HISTOGRAM_TIMERS = {
        "http.server.requests", "spring.kafka.listener", "spring.kafka.template"
    };

    @Bean
    public MeterFilter latencyHistogramFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                for (String timerName : HISTOGRAM_TIMERS) {
                    if (id.getName().startsWith(timerName)) {
                        return DistributionStatisticConfig.builder()
                                .percentilesHistogram(true)
                                .build()
                                .merge(config);
                    }
                }
                return config;
            }
        };
    }
}
