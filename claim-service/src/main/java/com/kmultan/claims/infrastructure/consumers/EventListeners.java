package com.kmultan.claims.infrastructure.consumers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.application.IdempotentConsumer;
import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.domain.Severity;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Reactions to other services' facts. Both topics are keyed by claim id, so
 * per-claim ordering holds; the IdempotentConsumer makes redelivery harmless.
 */
@Component
public class EventListeners {

    private static final Logger log = LoggerFactory.getLogger(EventListeners.class);

    private final ClaimService claims;
    private final IdempotentConsumer idempotent;
    private final ObjectMapper json;

    public EventListeners(ClaimService claims, IdempotentConsumer idempotent, ObjectMapper json) {
        this.claims = claims;
        this.idempotent = idempotent;
        this.json = json;
    }

    @KafkaListener(topics = "${claims.topics.assessment}", groupId = "${spring.kafka.consumer.group-id}")
    public void onAssessment(ConsumerRecord<String, String> record) throws IOException {
        try {
            AssessmentEvent e = json.readValue(record.value(), AssessmentEvent.class);
            if (!AssessmentEvent.ASSESSMENT_COMPLETED.equals(e.eventType())) {
                return;
            }
            idempotent.process(e.eventId(), e.eventType(), () -> claims.completeAssessment(e.claimId(),
                    new Assessment(Severity.valueOf(e.severity()), e.assessedAmount(), e.provider() + "/" + e.modelVersion())));
        } catch (RuntimeException | IOException ex) {
            log.error("Failed to handle {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), ex.toString(), ex);
            throw ex;
        }
    }

    @KafkaListener(topics = "${claims.topics.payout}", groupId = "${spring.kafka.consumer.group-id}")
    public void onPayout(ConsumerRecord<String, String> record) throws IOException {
        try {
            PayoutEvent e = json.readValue(record.value(), PayoutEvent.class);
            switch (e.type()) {
                case "PAYOUT_ISSUED" -> idempotent.process(e.eventId(), e.type(), () -> claims.acceptPayout(e.claimId()));
                case "PAYOUT_FAILED", "RESERVATION_REJECTED" ->
                        idempotent.process(e.eventId(), e.type(), () -> claims.markPayoutFailed(e.claimId(), e.reason()));
                default -> { /* FUNDS_RESERVED, FUNDS_RELEASED, PAYOUT_REVERSED: informational for us */ }
            }
        } catch (RuntimeException | IOException ex) {
            log.error("Failed to handle {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), ex.toString(), ex);
            throw ex;
        }
    }
}
