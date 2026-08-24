package com.kmultan.claims.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbox-side deduplication for consumed events. The processed row is inserted
 * in the same transaction as the handler's side effects (including the outbox
 * rows it writes), so a redelivered event is a clean no-op.
 */
@Component
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    @Entity
    @Table(name = "processed_message")
    public static class ProcessedMessage {
        @Id @Column(name = "message_id") UUID messageId;
        @Column(name = "message_type", nullable = false) String messageType;
        @Column(name = "processed_at", nullable = false) Instant processedAt = Instant.now();
        protected ProcessedMessage() {}
        ProcessedMessage(UUID id, String type) { this.messageId = id; this.messageType = type; }
    }

    private final EntityManager em;

    public IdempotentConsumer(EntityManager em) {
        this.em = em;
    }

    /** @return true if the handler ran, false if the message had already been processed */
    @Transactional
    public boolean process(UUID messageId, String messageType, Runnable handler) {
        if (em.find(ProcessedMessage.class, messageId) != null) {
            log.info("Duplicate {} {} — skipped", messageType, messageId);
            return false;
        }
        em.persist(new ProcessedMessage(messageId, messageType));
        handler.run();
        return true;
    }
}
