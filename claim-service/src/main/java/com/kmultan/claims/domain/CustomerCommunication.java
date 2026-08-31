package com.kmultan.claims.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message to the policyholder. The channel is simulated (no SMTP in the
 * demo) but the record is real: what was said, when, triggered by which fact —
 * the communication history an insurer must be able to show.
 */
@Entity
@Table(name = "customer_communication")
public class CustomerCommunication {

    public enum Type {
        CLAIM_RECEIVED,
        ASSESSMENT_COMPLETED,
        DECISION_APPROVED,
        AWAITING_SECOND_APPROVAL,
        DECISION_REJECTED,
        ADVANCE_PAID,
        CLAIM_PAID,
        PAYOUT_FAILED
    }

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Type type;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected CustomerCommunication() {}

    public CustomerCommunication(UUID claimId, Type type, String subject, String body) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.type = type;
        this.subject = subject;
        this.body = body;
        this.sentAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public Type getType() {
        return type;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
