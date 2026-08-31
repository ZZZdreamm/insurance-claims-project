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

/** One money movement to the policyholder; a claim settled with an advance has several. */
@Entity
@Table(name = "claim_payment")
public class ClaimPayment {

    public enum PaymentType {
        ADVANCE,
        FINAL
    }

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false)
    private PaymentType paymentType;

    private String reference;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected ClaimPayment() {}

    public ClaimPayment(UUID claimId, BigDecimal amount, PaymentType paymentType, String reference) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.amount = amount;
        this.paymentType = paymentType;
        this.reference = reference;
        this.issuedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentType getPaymentType() {
        return paymentType;
    }

    public String getReference() {
        return reference;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
