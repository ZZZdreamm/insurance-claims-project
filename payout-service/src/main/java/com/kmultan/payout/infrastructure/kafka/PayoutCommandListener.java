package com.kmultan.payout.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.application.PayoutCommand;
import com.kmultan.payout.application.PayoutCommandHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Commands are keyed by claim id, so all commands for one claim arrive on one
 * partition in order. Offsets are committed per record after the handler's
 * transaction commits (ack-mode RECORD), giving at-least-once delivery; the
 * handler's processed_message table turns that into effectively-once.
 */
@Component
public class PayoutCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PayoutCommandListener.class);

    private final PayoutCommandHandler handler;
    private final ObjectMapper json;

    public PayoutCommandListener(PayoutCommandHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = "${payout.commands-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> record) throws IOException {
        try {
            PayoutCommand cmd = json.readValue(record.value(), PayoutCommand.class);
            handler.handle(cmd);
        } catch (RuntimeException | IOException e) {
            log.error("Failed to handle {}-{}@{} key={}: {}", record.topic(), record.partition(), record.offset(), record.key(), e.toString(), e);
            throw e;
        }
    }
}
