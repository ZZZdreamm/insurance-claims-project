package com.kmultan.payout.infrastructure.kafka;

import java.io.IOException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.application.PayoutEvent;
import com.kmultan.payout.application.PayoutSaga;

/** The saga's second step reacts to this service's own FUNDS_RESERVED fact (separate consumer group). */
@Component
public class PayoutEventListener {

    private static final Logger log = LoggerFactory.getLogger(PayoutEventListener.class);

    private final PayoutSaga payoutSaga;
    private final ObjectMapper objectMapper;

    public PayoutEventListener(PayoutSaga payoutSaga, ObjectMapper objectMapper) {
        this.payoutSaga = payoutSaga;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${payout.events-topic}", groupId = "${spring.kafka.consumer.group-id}-self")
    public void onPayoutEvent(ConsumerRecord<String, String> consumerRecord) throws IOException {
        try {
            PayoutEvent event = objectMapper.readValue(consumerRecord.value(), PayoutEvent.class);
            if (event.type() == PayoutEvent.Type.FUNDS_RESERVED) {
                payoutSaga.onFundsReserved(event);
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
