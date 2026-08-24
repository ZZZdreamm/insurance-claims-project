package com.kmultan.payout.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.application.ClaimEventEnvelope;
import com.kmultan.payout.application.PayoutEvent;
import com.kmultan.payout.application.PayoutSaga;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Both topics are keyed by claim id, so everything about one claim is consumed
 * in order. Offsets commit per record after the saga's transaction (ack-mode
 * RECORD): at-least-once, made effectively-once by the processed_message table.
 */
@Component
public class ClaimEventsListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventsListener.class);

    private final PayoutSaga saga;
    private final ObjectMapper json;

    public ClaimEventsListener(PayoutSaga saga, ObjectMapper json) {
        this.saga = saga;
        this.json = json;
    }

    @KafkaListener(topics = "${payout.claims-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onClaimEvent(ConsumerRecord<String, String> record) throws IOException {
        try {
            ClaimEventEnvelope e = json.readValue(record.value(), ClaimEventEnvelope.class);
            switch (e.eventType()) {
                case ClaimEventEnvelope.CLAIM_APPROVED -> saga.onClaimApproved(e);
                case ClaimEventEnvelope.PAYOUT_UNACCEPTED -> saga.onPayoutUnaccepted(e);
                default -> { /* not our business */ }
            }
        } catch (RuntimeException | IOException ex) {
            log.error("Failed to handle {}-{}@{} key={}: {}", record.topic(), record.partition(), record.offset(), record.key(), ex.toString(), ex);
            throw ex;
        }
    }

    @KafkaListener(topics = "${payout.events-topic}", groupId = "${spring.kafka.consumer.group-id}-self")
    public void onPayoutEvent(ConsumerRecord<String, String> record) throws IOException {
        try {
            PayoutEvent e = json.readValue(record.value(), PayoutEvent.class);
            if (e.type() == PayoutEvent.Type.FUNDS_RESERVED) {
                saga.onFundsReserved(e);
            }
        } catch (RuntimeException | IOException ex) {
            log.error("Failed to handle {}-{}@{} key={}: {}", record.topic(), record.partition(), record.offset(), record.key(), ex.toString(), ex);
            throw ex;
        }
    }
}
