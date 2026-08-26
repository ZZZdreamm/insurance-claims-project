package com.kmultan.claims.infrastructure.consumers;

import java.io.IOException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.application.IdempotentConsumer;

/**
 * Reactions to payout-service's facts. The topic is keyed by claim id, so
 * per-claim ordering holds; the IdempotentConsumer makes redelivery harmless.
 */
@Component
public class PayoutEventListener {

    private static final Logger log = LoggerFactory.getLogger(PayoutEventListener.class);

    private final ClaimService claimService;
    private final IdempotentConsumer idempotentConsumer;
    private final ObjectMapper objectMapper;

    public PayoutEventListener(
            ClaimService claimService, IdempotentConsumer idempotentConsumer, ObjectMapper objectMapper) {
        this.claimService = claimService;
        this.idempotentConsumer = idempotentConsumer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${claims.topics.payout}", groupId = "${spring.kafka.consumer.group-id}")
    public void onPayoutEvent(ConsumerRecord<String, String> consumerRecord) throws IOException {
        try {
            PayoutEvent event = objectMapper.readValue(consumerRecord.value(), PayoutEvent.class);
            switch (event.type()) {
                case PayoutEvent.PAYOUT_ISSUED -> idempotentConsumer.process(
                        event.eventId(), event.type(), () -> claimService.acceptPayout(event.claimId()));
                case PayoutEvent.PAYOUT_FAILED, PayoutEvent.RESERVATION_REJECTED -> idempotentConsumer.process(
                        event.eventId(),
                        event.type(),
                        () -> claimService.markPayoutFailed(event.claimId(), event.reason()));
                default -> {
                    /* FUNDS_RESERVED, FUNDS_RELEASED, PAYOUT_REVERSED: informational for this service */
                }
            }
        } catch (RuntimeException | IOException exception) {
            log.error(
                    "Failed to handle {}-{}@{}: {}",
                    consumerRecord.topic(),
                    consumerRecord.partition(),
                    consumerRecord.offset(),
                    exception.toString(),
                    exception);
            throw exception;
        }
    }
}
