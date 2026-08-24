package com.kmultan.claims.infrastructure.outbox;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * "One claim = one trace": the trace that submits the claim must be the trace
 * the Kafka consumers see, even though the outbox relay runs on another thread
 * a second later.
 */
class TracePropagationIT extends AbstractIntegrationTest {

    @Autowired ClaimService service;
    @Autowired OutboxRepository outbox;
    @Autowired Tracer tracer;

    @Test
    void kafkaRecordCarriesTheOriginatingTraceId() {
        Span span = tracer.nextSpan().name("test submit").start();
        Claim claim;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            claim = service.submit("POL-TR", "TR 1", LocalDate.now(), "Trace propagation integration test", null, List.of());
        } finally {
            span.end();
        }
        String traceId = span.context().traceId();

        OutboxEvent row = outbox.findByAggregateIdOrderById(claim.getId()).get(0);
        assertThat(row.getTraceParent()).contains(traceId);

        Map<String, Object> props = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "trace-it-" + UUID.randomUUID(), "true");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of("claims.events"));
            String[] header = new String[1];
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    Header h = r.headers().lastHeader("traceparent");
                    if (header[0] == null && r.key().equals(claim.getId().toString()) && h != null) {
                        header[0] = new String(h.value());
                    }
                }
                assertThat(header[0]).isNotNull();
            });
            assertThat(header[0]).contains(traceId);
        }
    }
}
