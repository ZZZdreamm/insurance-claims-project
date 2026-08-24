package com.kmultan.payout.infrastructure.kafka;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** W3C trace-context propagation for HTTP and Kafka headers (traceparent / tracestate). */
@Configuration
public class TracingConfig {
    @Bean
    TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }
}
