package com.kmultan.claims.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kmultan.claims.domain.ProcessedMessage;
import com.kmultan.claims.domain.ProcessedMessageRepository;

/**
 * Inbox-side deduplication for consumed events. The processed row is inserted
 * in the same transaction as the handler's side effects (including the outbox
 * rows it writes), so a redelivered event is a clean no-op.
 */
@Component
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final ProcessedMessageRepository processedMessages;

    public IdempotentConsumer(ProcessedMessageRepository processedMessages) {
        this.processedMessages = processedMessages;
    }

    /** @return true if the handler ran, false if the message had already been processed */
    @Transactional
    public boolean process(UUID messageId, String messageType, Runnable handler) {
        if (processedMessages.existsById(messageId)) {
            log.info("Duplicate {} {} — skipped", messageType, messageId);
            return false;
        }
        processedMessages.save(new ProcessedMessage(messageId, messageType));
        handler.run();
        return true;
    }
}
