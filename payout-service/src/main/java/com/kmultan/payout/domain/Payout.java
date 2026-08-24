package com.kmultan.payout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payout")
public class Payout {
    public enum Status { ISSUED, REVERSED, FAILED }

    @Id @Column(name = "claim_id") private UUID claimId;
    @Column(nullable = false) private BigDecimal amount;
    private String reference;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    private String reason;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected Payout() {}

    public static Payout issued(UUID claimId, BigDecimal amount, String reference) {
        Payout p = new Payout(); p.claimId = claimId; p.amount = amount; p.reference = reference; p.status = Status.ISSUED; return p;
    }

    public static Payout failed(UUID claimId, BigDecimal amount, String reason) {
        Payout p = new Payout(); p.claimId = claimId; p.amount = amount; p.reason = reason; p.status = Status.FAILED; return p;
    }

    public void reverse() { status = Status.REVERSED; updatedAt = Instant.now(); }

    public UUID getClaimId() { return claimId; }
    public BigDecimal getAmount() { return amount; }
    public String getReference() { return reference; }
    public Status getStatus() { return status; }
    public String getReason() { return reason; }
}
