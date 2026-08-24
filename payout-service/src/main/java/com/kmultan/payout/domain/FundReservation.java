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
@Table(name = "fund_reservation")
public class FundReservation {
    public enum Status { RESERVED, RELEASED, SETTLED }

    @Id @Column(name = "claim_id") private UUID claimId;
    @Column(nullable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected FundReservation() {}

    public FundReservation(UUID claimId, BigDecimal amount) {
        this.claimId = claimId;
        this.amount = amount;
        this.status = Status.RESERVED;
    }

    public void release() { status = Status.RELEASED; updatedAt = Instant.now(); }
    public void settle() { status = Status.SETTLED; updatedAt = Instant.now(); }

    public UUID getClaimId() { return claimId; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
}
