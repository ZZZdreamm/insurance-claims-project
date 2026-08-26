package com.kmultan.claims.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.platform.kafka.KafkaTestConsumer;
import com.kmultan.platform.outbox.OutboxEvent;
import com.kmultan.platform.outbox.OutboxEventRepository;
import com.kmultan.platform.outbox.OutboxTraceContext;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * "One claim = one trace": the trace that submits the claim must be the trace
 * the Kafka consumers see, even though the outbox relay runs on another thread
 * a second later.
 */
class TracePropagationIT extends AbstractIntegrationTest {

    @Autowired
    ClaimService claimService;

    @Autowired
    OutboxEventRepository outboxEvents;

    @Autowired
    Tracer tracer;

    @Value("${claims.topics.claims}")
    String claimsTopic;

    @Test
    void kafkaRecordCarriesTheOriginatingTraceId() {
        Span submitSpan = tracer.nextSpan().name("test submit").start();
        Claim claim;
        try (Tracer.SpanInScope ignored = tracer.withSpan(submitSpan)) {
            claim = claimService.submit(
                    "POL-TR", "TR 1", LocalDate.now(), "Trace propagation integration test", null, List.of());
        } finally {
            submitSpan.end();
        }
        String traceId = submitSpan.context().traceId();

        OutboxEvent submittedRow =
                outboxEvents.findByAggregateIdOrderById(claim.getId()).get(0);
        assertThat(submittedRow.getTraceParent()).contains(traceId);

        try (KafkaTestConsumer claimsTopicConsumer = new KafkaTestConsumer(KAFKA.getBootstrapServers(), claimsTopic)) {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                            claimsTopicConsumer.pollForKey(claim.getId().toString()))
                    .isNotEmpty());
            ConsumerRecord<String, String> submitted =
                    claimsTopicConsumer.pollForKey(claim.getId().toString()).get(0);
            Header traceparent = submitted.headers().lastHeader(OutboxTraceContext.TRACEPARENT_HEADER);
            assertThat(traceparent).isNotNull();
            assertThat(new String(traceparent.value())).contains(traceId);
        }
    }
}
