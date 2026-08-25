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

/** Reaction to assessment-service's ASSESSMENT_COMPLETED: the claim moves to review. */
@Component
public class AssessmentEventListener {

    private static final Logger log = LoggerFactory.getLogger(AssessmentEventListener.class);

    private final ClaimService claimService;
    private final IdempotentConsumer idempotentConsumer;
    private final ObjectMapper objectMapper;

    public AssessmentEventListener(ClaimService claimService, IdempotentConsumer idempotentConsumer, ObjectMapper objectMapper) {
        this.claimService = claimService;
        this.idempotentConsumer = idempotentConsumer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${claims.topics.assessment}", groupId = "${spring.kafka.consumer.group-id}")
    public void onAssessmentEvent(ConsumerRecord<String, String> consumerRecord) throws IOException {
        try {
            AssessmentEvent event = objectMapper.readValue(consumerRecord.value(), AssessmentEvent.class);
            if (!AssessmentEvent.ASSESSMENT_COMPLETED.equals(event.eventType())) {
                return;
            }
            Assessment assessment = new Assessment(Severity.valueOf(event.severity()), event.assessedAmount(),
                    event.provider() + "/" + event.modelVersion());
            idempotentConsumer.process(event.eventId(), event.eventType(), () -> claimService.completeAssessment(event.claimId(), assessment));
        } catch (RuntimeException | IOException exception) {
            log.error("Failed to handle {}-{}@{}: {}", consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(), exception.toString(), exception);
            throw exception;
        }
    }
}
