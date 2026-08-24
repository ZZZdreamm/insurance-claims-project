package com.kmultan.claims.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OutboxIT extends AbstractIntegrationTest {

    @Autowired ClaimService service;
    @Autowired ClaimRepository claims;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Value("${claims.topics.claims}") String topic;

    Consumer<String, String> consumer;

    @BeforeEach
    void subscribe() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "outbox-it-" + UUID.randomUUID(), "true");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(topic));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void eventIsWrittenInSameTransactionAndRelayedToKafka() throws Exception {
        Claim claim = service.submit("POL-OB", "OB 1234", LocalDate.now(), "Outbox integration test claim", new BigDecimal("100"), List.of());

        // the process may already have produced assessment events; the first row is always the submission
        List<OutboxEvent> rows = outbox.findByAggregateIdOrderById(claim.getId());
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).getEventType()).isEqualTo("CLAIM_SUBMITTED");

        ConsumerRecord<String, String> record = awaitRecordFor(claim.getId());
        assertThat(record.key()).isEqualTo(claim.getId().toString());
        assertThat(new String(record.headers().lastHeader("eventType").value())).isEqualTo("CLAIM_SUBMITTED");
        JsonNode payload = json.readTree(record.value());
        assertThat(payload.get("claim").get("plateNumber").asText()).isEqualTo("OB1234");
        assertThat(payload.get("eventId").asText()).isEqualTo(rows.get(0).getEventId().toString());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outbox.findByAggregateIdOrderById(claim.getId()).get(0).getPublishedAt()).isNotNull());
    }

    @Test
    void rollbackDiscardsBothAggregateAndEvent() {
        long before = outbox.count();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            service.submit("POL-RB", "RB 1", LocalDate.now(), "This transaction will roll back", null, List.of());
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outbox.count()).isEqualTo(before);
        assertThat(claims.findAll()).noneMatch(c -> c.getPolicyNumber().equals("POL-RB"));
    }

    @Test
    void eventsForOneClaimArriveInOrderOnOnePartition() throws Exception {
        Claim claim = service.submit("POL-ORD", "ORD 1", LocalDate.now(), "Ordering integration test claim", null, List.of());
        // the fake assessment-service answers over Kafka; then approve like an adjuster would
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(service.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW));
        service.claimReview(claim.getId(), "alice");
        service.approve(claim.getId(), new BigDecimal("250"));

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
            polled.forEach(r -> { if (r.key().equals(claim.getId().toString())) records.add(r); });
            assertThat(records).hasSize(4);
        });

        assertThat(records).extracting(r -> new String(r.headers().lastHeader("eventType").value()))
                .containsExactly("CLAIM_SUBMITTED", "ASSESSMENT_COMPLETED", "REVIEW_CLAIMED", "CLAIM_APPROVED");
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.get(0).partition());
        assertThat(records).extracting(r -> Long.parseLong(new String(r.headers().lastHeader("sequence").value())))
                .isSorted();
    }

    private ConsumerRecord<String, String> awaitRecordFor(UUID claimId) {
        ConsumerRecord<String, String>[] found = new ConsumerRecord[1];
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
            // keep the FIRST record for the claim: later process-driven events may already be on the topic
            polled.forEach(r -> { if (found[0] == null && r.key().equals(claimId.toString())) found[0] = r; });
            assertThat(found[0]).isNotNull();
        });
        return found[0];
    }
}
