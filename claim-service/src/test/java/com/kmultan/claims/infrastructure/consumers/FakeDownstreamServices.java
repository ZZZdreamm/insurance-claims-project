package com.kmultan.claims.infrastructure.consumers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal in-JVM stand-ins for assessment-service and payout-service, using
 * the same deterministic rules as the real ones:
 * <ul>
 *   <li>descriptions containing "NOASSESS" get no triage result (exercises the timeout fallback)</li>
 *   <li>approved amounts over 50,000 cannot be reserved; amounts ending in .99 fail at the provider</li>
 * </ul>
 * Records every claim event seen, so tests can assert on the published facts.
 */
@TestComponent
public class FakeDownstreamServices {

    private static final BigDecimal RESERVE_LIMIT = new BigDecimal("50000");
    private static final BigDecimal PROVIDER_REJECTS_CENTS = new BigDecimal("0.99");

    public final List<JsonNode> claimEvents = new CopyOnWriteArrayList<>();

    /** Several Spring test contexts may be cached in one JVM; only the first fake answers, the others just record. */
    private static final AtomicReference<FakeDownstreamServices> RESPONDER = new AtomicReference<>();

    private final boolean responder;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String assessmentTopic;
    private final String payoutTopic;

    public FakeDownstreamServices(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${claims.topics.assessment}") String assessmentTopic,
            @Value("${claims.topics.payout}") String payoutTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.assessmentTopic = assessmentTopic;
        this.payoutTopic = payoutTopic;
        this.responder = RESPONDER.compareAndSet(null, this);
    }

    @KafkaListener(topics = "${claims.topics.claims}", groupId = "fake-downstream-#{T(java.util.UUID).randomUUID()}")
    public void onClaimEvent(ConsumerRecord<String, String> consumerRecord) throws Exception {
        JsonNode event = objectMapper.readTree(consumerRecord.value());
        claimEvents.add(event);
        if (!responder) {
            return;
        }
        String eventType = event.get("eventType").asText();
        String claimId = event.get("claimId").asText();
        JsonNode claim = event.get("claim");
        switch (eventType) {
            case "CLAIM_SUBMITTED" -> {
                if (claim.get("description").asText().contains("NOASSESS")) {
                    return;
                }
                String severity =
                        claim.get("description").asText().toLowerCase().contains("fire") ? "SEVERE" : "MODERATE";
                send(
                        assessmentTopic,
                        claimId,
                        """
                        {"eventId":"%s","eventType":"ASSESSMENT_COMPLETED","claimId":"%s","severity":"%s","assessedAmount":1500.00,
                         "provider":"fake-assessment","modelVersion":"test","occurredAt":"%s"}"""
                                .formatted(UUID.randomUUID(), claimId, severity, Instant.now()));
            }
            case "CLAIM_APPROVED" -> {
                BigDecimal amount = new BigDecimal(claim.get("approvedAmount").asText());
                if (amount.compareTo(RESERVE_LIMIT) > 0) {
                    payoutEvent(claimId, event, "RESERVATION_REJECTED", null, "Amount exceeds reserve limit");
                } else if (amount.remainder(BigDecimal.ONE).compareTo(PROVIDER_REJECTS_CENTS) == 0) {
                    payoutEvent(claimId, event, "FUNDS_RESERVED", null, null);
                    payoutEvent(claimId, event, "PAYOUT_FAILED", null, "Payment provider rejected the transfer");
                    payoutEvent(claimId, event, "FUNDS_RELEASED", null, null);
                } else {
                    payoutEvent(claimId, event, "FUNDS_RESERVED", null, null);
                    payoutEvent(claimId, event, "PAYOUT_ISSUED", "PAY-" + claimId.substring(0, 8), null);
                }
            }
            case "PAYOUT_UNACCEPTED" -> payoutEvent(claimId, event, "PAYOUT_REVERSED", null, null);
            default -> {}
        }
    }

    private void payoutEvent(String claimId, JsonNode cause, String eventType, String reference, String reason)
            throws Exception {
        send(
                payoutTopic,
                claimId,
                """
                {"eventId":"%s","type":"%s","claimId":"%s","causationEventId":"%s","reference":%s,"reason":%s,"occurredAt":"%s"}"""
                        .formatted(
                                UUID.randomUUID(),
                                eventType,
                                claimId,
                                cause.get("eventId").asText(),
                                reference == null ? "null" : "\"" + reference + "\"",
                                reason == null ? "null" : "\"" + reason + "\"",
                                Instant.now()));
    }

    private void send(String topic, String key, String body) throws Exception {
        kafkaTemplate.send(topic, key, body).get();
    }

    public List<String> eventTypesFor(UUID claimId) {
        return claimEvents.stream()
                .filter(event -> event.get("claimId").asText().equals(claimId.toString()))
                .map(event -> event.get("eventType").asText())
                .toList();
    }
}
