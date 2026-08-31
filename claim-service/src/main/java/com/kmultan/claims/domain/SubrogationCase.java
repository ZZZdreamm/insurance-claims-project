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
 * Recovery of what the insurer paid from the party actually liable (typically
 * the at-fault driver's insurer). Opened after payout, accumulates recoveries
 * until the expected amount is back, or is written off with a reason.
 */
@Entity
@Table(name = "subrogation_case")
public class SubrogationCase {

    public enum Status {
        OPEN,
        RECOVERED,
        WRITTEN_OFF
    }

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false, updatable = false, unique = true)
    private UUID claimId;

    @Column(name = "liable_party", nullable = false)
    private String liableParty;

    @Column(name = "expected_amount", nullable = false)
    private BigDecimal expectedAmount;

    @Column(name = "recovered_amount", nullable = false)
    private BigDecimal recoveredAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "write_off_reason", length = 500)
    private String writeOffReason;

    @Column(name = "opened_by", nullable = false)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SubrogationCase() {}

    public static SubrogationCase open(UUID claimId, String liableParty, BigDecimal expectedAmount, String openedBy) {
        if (expectedAmount == null || expectedAmount.signum() <= 0) {
            throw new IllegalArgumentException("Expected recovery amount must be positive");
        }
        SubrogationCase recovery = new SubrogationCase();
        recovery.id = UUID.randomUUID();
        recovery.claimId = claimId;
        recovery.liableParty = liableParty;
        recovery.expectedAmount = expectedAmount;
        recovery.status = Status.OPEN;
        recovery.openedBy = openedBy;
        recovery.openedAt = Instant.now();
        recovery.updatedAt = recovery.openedAt;
        return recovery;
    }

    /** @return true when this recovery completed the case */
    public boolean recordRecovery(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Recovered amount must be positive");
        }
        if (status != Status.OPEN) {
            throw new IllegalStateException("Subrogation case is already " + status);
        }
        this.recoveredAmount = recoveredAmount.add(amount);
        this.updatedAt = Instant.now();
        if (recoveredAmount.compareTo(expectedAmount) >= 0) {
            this.status = Status.RECOVERED;
            return true;
        }
        return false;
    }

    public void writeOff(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A write-off needs a reason");
        }
        if (status != Status.OPEN) {
            throw new IllegalStateException("Subrogation case is already " + status);
        }
        this.status = Status.WRITTEN_OFF;
        this.writeOffReason = reason;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public String getLiableParty() {
        return liableParty;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public BigDecimal getRecoveredAmount() {
        return recoveredAmount;
    }

    public Status getStatus() {
        return status;
    }

    public String getWriteOffReason() {
        return writeOffReason;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
