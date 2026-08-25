package com.kmultan.claims.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.platform.kafka.KafkaTestConsumer;
import com.kmultan.platform.outbox.OutboxEvent;
import com.kmultan.platform.outbox.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OutboxIT extends AbstractIntegrationTest {

    @Autowired ClaimService claimService;
    @Autowired ClaimRepository claimRepository;
    @Autowired OutboxEventRepository outboxEvents;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ObjectMapper objectMapper;
    @Value("${claims.topics.claims}") String claimsTopic;

    KafkaTestConsumer claimsTopicConsumer;

    @BeforeEach
    void subscribe() {
        claimsTopicConsumer = new KafkaTestConsumer(KAFKA.getBootstrapServers(), claimsTopic);
    }

    @AfterEach
    void close() {
        claimsTopicConsumer.close();
    }

    private static String headerValue(ConsumerRecord<String, String> consumerRecord, String name) {
        return new String(consumerRecord.headers().lastHeader(name).value());
    }

    @Test
    void eventIsWrittenInSameTransactionAndRelayedToKafka() throws Exception {
        Claim claim = claimService.submit("POL-OB", "OB 1234", LocalDate.now(), "Outbox integration test claim", new BigDecimal("100"), List.of());

        // the fake assessment may already have produced further events; the first row is always the submission
        List<OutboxEvent> rows = outboxEvents.findByAggregateIdOrderById(claim.getId());
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).getEventType()).isEqualTo("CLAIM_SUBMITTED");

        List<ConsumerRecord<String, String>> records = awaitRecords(claim, 1);
        ConsumerRecord<String, String> submitted = records.get(0);
        assertThat(submitted.key()).isEqualTo(claim.getId().toString());
        assertThat(headerValue(submitted, "eventType")).isEqualTo("CLAIM_SUBMITTED");
        JsonNode payload = objectMapper.readTree(submitted.value());
        assertThat(payload.get("claim").get("plateNumber").asText()).isEqualTo("OB1234");
        assertThat(payload.get("eventId").asText()).isEqualTo(rows.get(0).getEventId().toString());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(outboxEvents.findByAggregateIdOrderById(claim.getId()).get(0).getPublishedAt()).isNotNull());
    }

    @Test
    void rollbackDiscardsBothAggregateAndEvent() {
        long rowsBefore = outboxEvents.count();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            claimService.submit("POL-RB", "RB 1", LocalDate.now(), "This transaction will roll back", null, List.of());
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outboxEvents.count()).isEqualTo(rowsBefore);
        assertThat(claimRepository.findAll()).noneMatch(claim -> claim.getPolicyNumber().equals("POL-RB"));
    }

    @Test
    void eventsForOneClaimArriveInOrderOnOnePartition() {
        Claim claim = claimService.submit("POL-ORD", "ORD 1", LocalDate.now(), "Ordering integration test claim", null, List.of());
        // the fake assessment-service answers over Kafka; then approve like an adjuster would
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(claimService.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW));
        claimService.claimReview(claim.getId(), "alice");
        claimService.approve(claim.getId(), new BigDecimal("250"));

        List<ConsumerRecord<String, String>> records = awaitRecords(claim, 4);
        assertThat(records.subList(0, 4)).extracting(consumerRecord -> headerValue(consumerRecord, "eventType"))
                .containsExactly("CLAIM_SUBMITTED", "ASSESSMENT_COMPLETED", "REVIEW_CLAIMED", "CLAIM_APPROVED");
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.get(0).partition());
        assertThat(records).extracting(consumerRecord -> Long.parseLong(headerValue(consumerRecord, "sequence"))).isSorted();
    }

    private List<ConsumerRecord<String, String>> awaitRecords(Claim claim, int atLeast) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(claimsTopicConsumer.pollForKey(claim.getId().toString())).hasSizeGreaterThanOrEqualTo(atLeast));
        return claimsTopicConsumer.pollForKey(claim.getId().toString());
    }
}
