package com.kmultan.claims.infrastructure.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

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

    public final List<JsonNode> claimEvents = new CopyOnWriteArrayList<>();

    /** Several Spring test contexts may be cached in one JVM; only the first fake answers, the others just record. */
    private static final AtomicReference<FakeDownstreamServices> RESPONDER = new AtomicReference<>();
    private final boolean responder;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String assessmentTopic;
    private final String payoutTopic;

    public FakeDownstreamServices(KafkaTemplate<String, String> kafka, ObjectMapper json,
                                  @Value("${claims.topics.assessment}") String assessmentTopic,
                                  @Value("${claims.topics.payout}") String payoutTopic) {
        this.kafka = kafka;
        this.json = json;
        this.assessmentTopic = assessmentTopic;
        this.payoutTopic = payoutTopic;
        this.responder = RESPONDER.compareAndSet(null, this);
    }

    @KafkaListener(topics = "${claims.topics.claims}", groupId = "fake-downstream-#{T(java.util.UUID).randomUUID()}")
    public void onClaimEvent(ConsumerRecord<String, String> record) throws Exception {
        JsonNode e = json.readTree(record.value());
        claimEvents.add(e);
        if (!responder) return;
        String type = e.get("eventType").asText();
        String claimId = e.get("claimId").asText();
        JsonNode claim = e.get("claim");
        switch (type) {
            case "CLAIM_SUBMITTED" -> {
                if (claim.get("description").asText().contains("NOASSESS")) return;
                String severity = claim.get("description").asText().toLowerCase().contains("fire") ? "SEVERE" : "MODERATE";
                send(assessmentTopic, claimId, """
                        {"eventId":"%s","eventType":"ASSESSMENT_COMPLETED","claimId":"%s","severity":"%s","assessedAmount":1500.00,
                         "provider":"fake-assessment","modelVersion":"test","occurredAt":"%s"}"""
                        .formatted(UUID.randomUUID(), claimId, severity, Instant.now()));
            }
            case "CLAIM_APPROVED" -> {
                BigDecimal amount = new BigDecimal(claim.get("approvedAmount").asText());
                if (amount.compareTo(new BigDecimal("50000")) > 0) {
                    payout(claimId, e, "RESERVATION_REJECTED", null, "Amount exceeds reserve limit");
                } else if (amount.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.99")) == 0) {
                    payout(claimId, e, "FUNDS_RESERVED", null, null);
                    payout(claimId, e, "PAYOUT_FAILED", null, "Payment provider rejected the transfer");
                    payout(claimId, e, "FUNDS_RELEASED", null, null);
                } else {
                    payout(claimId, e, "FUNDS_RESERVED", null, null);
                    payout(claimId, e, "PAYOUT_ISSUED", "PAY-" + claimId.substring(0, 8), null);
                }
            }
            case "PAYOUT_UNACCEPTED" -> payout(claimId, e, "PAYOUT_REVERSED", null, null);
            default -> { }
        }
    }

    private void payout(String claimId, JsonNode cause, String type, String reference, String reason) throws Exception {
        send(payoutTopic, claimId, """
                {"eventId":"%s","type":"%s","claimId":"%s","causationEventId":"%s","reference":%s,"reason":%s,"occurredAt":"%s"}"""
                .formatted(UUID.randomUUID(), type, claimId, cause.get("eventId").asText(),
                        reference == null ? "null" : "\"" + reference + "\"", reason == null ? "null" : "\"" + reason + "\"", Instant.now()));
    }

    private void send(String topic, String key, String body) throws Exception {
        kafka.send(topic, key, body).get();
    }

    public List<String> eventTypesFor(UUID claimId) {
        return claimEvents.stream().filter(e -> e.get("claimId").asText().equals(claimId.toString()))
                .map(e -> e.get("eventType").asText()).toList();
    }
}
