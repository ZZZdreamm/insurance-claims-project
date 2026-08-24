package com.kmultan.claims.infrastructure.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.application.payout.PayoutReply;
import com.kmultan.claims.application.workflow.ClaimWorkflow;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Feeds payout-service replies back into the waiting process instance. */
@Component
public class PayoutReplyListener {

    private static final Logger log = LoggerFactory.getLogger(PayoutReplyListener.class);

    private final ClaimWorkflow workflow;
    private final ObjectMapper json;

    public PayoutReplyListener(ClaimWorkflow workflow, ObjectMapper json) {
        this.workflow = workflow;
        this.json = json;
    }

    @KafkaListener(topics = "${claims.payout.replies-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> record) throws IOException {
        try {
            PayoutReply reply = json.readValue(record.value(), PayoutReply.class);
            workflow.onPayoutReply(reply);
        } catch (RuntimeException | IOException e) {
            log.error("Failed to handle payout reply {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), e.toString(), e);
            throw e;
        }
    }
}
