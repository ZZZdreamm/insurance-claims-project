package com.kmultan.platform.outbox;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Bridges trace context across the outbox gap. The HTTP request that changes a
 * claim ends at commit; the relay runs later on a scheduler thread. Without
 * this, every Kafka consumer would start a fresh trace and "one claim = one
 * trace" would be a lie. We serialise the W3C {@code traceparent} into the row
 * and re-activate it around the send, so the Kafka producer observation injects
 * the original trace into the record headers.
 */
@Component
public class OutboxTraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** @return the current span's W3C traceparent header value, or null when not traced */
    public String current() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT_HEADER);
    }

    public <T> T runInTrace(String traceParent, String spanName, Callable<T> work) throws Exception {
        if (traceParent == null) {
            return work.call();
        }
        Map<String, String> carrier = Map.of(TRACEPARENT_HEADER, traceParent);
        Span span = propagator
                .extract(carrier, (headers, key) -> headers.get(key))
                .name(spanName)
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.call();
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
