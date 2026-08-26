package com.kmultan.payout.infrastructure.kafka;

import java.io.IOException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.application.ClaimEventEnvelope;
import com.kmultan.payout.application.PayoutSaga;

/**
 * Reactions to claim-service facts. The topic is keyed by claim id, so
 * everything about one claim is consumed in order. Offsets commit per record
 * after the saga's transaction (ack-mode RECORD): at-least-once, made
 * effectively-once by the processed_message table.
 */
@Component
public class ClaimEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventListener.class);

    private final PayoutSaga payoutSaga;
    private final ObjectMapper objectMapper;

    public ClaimEventListener(PayoutSaga payoutSaga, ObjectMapper objectMapper) {
        this.payoutSaga = payoutSaga;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${payout.claims-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onClaimEvent(ConsumerRecord<String, String> consumerRecord) throws IOException {
        try {
            ClaimEventEnvelope event = objectMapper.readValue(consumerRecord.value(), ClaimEventEnvelope.class);
            switch (event.eventType()) {
                case ClaimEventEnvelope.CLAIM_APPROVED -> payoutSaga.onClaimApproved(event);
                case ClaimEventEnvelope.PAYOUT_UNACCEPTED -> payoutSaga.onPayoutUnaccepted(event);
                default -> {
                    /* not this service's business */
                }
            }
        } catch (RuntimeException | IOException exception) {
            log.error(
                    "Failed to handle {}-{}@{} key={}: {}",
                    consumerRecord.topic(),
                    consumerRecord.partition(),
                    consumerRecord.offset(),
                    consumerRecord.key(),
                    exception.toString(),
                    exception);
            throw exception;
        }
    }
}
