package com.kmultan.platform.tracing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;

/**
 * W3C trace-context propagation ({@code traceparent} / {@code tracestate}) on
 * HTTP and Kafka headers. Spring Boot 3.5 does not register an injector on its
 * own in this setup; without this bean no header ever carries the trace.
 */
@Configuration
public class TracePropagationConfiguration {

    @Bean
    public TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }
}
