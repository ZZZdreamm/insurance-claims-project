package com.kmultan.claims.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Inbox record: one row per consumed event, inserted in the same transaction as the handler's side effects. */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedMessage() {}

    public ProcessedMessage(UUID messageId, String messageType) {
        this.messageId = messageId;
        this.messageType = messageType;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getMessageType() {
        return messageType;
    }
}
