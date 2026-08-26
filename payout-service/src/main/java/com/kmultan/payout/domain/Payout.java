package com.kmultan.payout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payout")
public class Payout {
    public enum Status {
        PENDING,
        ISSUED,
        REVERSED,
        FAILED
    }

    @Id
    @Column(name = "claim_id")
    private UUID claimId;

    @Column(nullable = false)
    private BigDecimal amount;

    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "causation_event_id")
    private UUID causationEventId;

    protected Payout() {}

    public static Payout pending(UUID claimId) {
        Payout payout = new Payout();
        payout.claimId = claimId;
        payout.amount = BigDecimal.ZERO;
        payout.status = Status.PENDING;
        return payout;
    }

    public void issued(BigDecimal amount, String reference, UUID causationEventId) {
        this.amount = amount;
        this.reference = reference;
        this.reason = null;
        this.status = Status.ISSUED;
        this.causationEventId = causationEventId;
        this.updatedAt = Instant.now();
    }

    public void failed(BigDecimal amount, String reason, UUID causationEventId) {
        this.amount = amount;
        this.reason = reason;
        this.status = Status.FAILED;
        this.causationEventId = causationEventId;
        this.updatedAt = Instant.now();
    }

    public void reverse() {
        status = Status.REVERSED;
        updatedAt = Instant.now();
    }

    public UUID getClaimId() {
        return claimId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReference() {
        return reference;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }
}
