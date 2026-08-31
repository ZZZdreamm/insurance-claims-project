package com.kmultan.claims.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The insurer's expected remaining cost of an open claim. Opened at
 * registration from the estimate, adjusted when the assessment arrives and
 * when the settlement is fixed, reduced as money goes out, and closed with the
 * claim: SETTLED when fully paid, RELEASED on rejection or withdrawal.
 */
@Entity
@Table(name = "claim_reserve")
public class ClaimReserve {

    public enum Status {
        OPEN,
        SETTLED,
        RELEASED
    }

    @Id
    @Column(name = "claim_id")
    private UUID claimId;

    @Column(name = "initial_amount", nullable = false)
    private BigDecimal initialAmount;

    @Column(name = "current_amount", nullable = false)
    private BigDecimal currentAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClaimReserve() {}

    public static ClaimReserve open(UUID claimId, BigDecimal initialAmount) {
        ClaimReserve reserve = new ClaimReserve();
        reserve.claimId = claimId;
        reserve.initialAmount = initialAmount;
        reserve.currentAmount = initialAmount;
        reserve.status = Status.OPEN;
        reserve.openedAt = Instant.now();
        reserve.updatedAt = reserve.openedAt;
        return reserve;
    }

    public void adjustTo(BigDecimal newAmount) {
        if (status != Status.OPEN) {
            return;
        }
        this.currentAmount = newAmount.max(BigDecimal.ZERO);
        this.updatedAt = Instant.now();
    }

    public void settle() {
        this.currentAmount = BigDecimal.ZERO;
        this.status = Status.SETTLED;
        this.updatedAt = Instant.now();
    }

    public void release() {
        this.currentAmount = BigDecimal.ZERO;
        this.status = Status.RELEASED;
        this.updatedAt = Instant.now();
    }

    public UUID getClaimId() {
        return claimId;
    }

    public BigDecimal getInitialAmount() {
        return initialAmount;
    }

    public BigDecimal getCurrentAmount() {
        return currentAmount;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
