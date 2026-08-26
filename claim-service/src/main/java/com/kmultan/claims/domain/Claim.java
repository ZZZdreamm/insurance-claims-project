package com.kmultan.claims.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Claim aggregate root. State changes go through behaviour methods so
 * invariants (legal transitions, amount rules) live in one place.
 *
 * {@code @Version} gives optimistic locking: two adjusters acting on the same
 * claim concurrently cannot both win — the second write fails with
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
 */
@Entity
@Table(name = "claim")
@EntityListeners(AuditingEntityListener.class)
public class Claim {

    @Id
    private UUID id;

    @Column(name = "claim_number", nullable = false, unique = true, updatable = false)
    private String claimNumber;

    @Column(name = "policy_number", nullable = false, updatable = false)
    private String policyNumber;

    @Column(name = "plate_number", nullable = false, updatable = false)
    private String plateNumber;

    @Column(name = "incident_date", nullable = false, updatable = false)
    private LocalDate incidentDate;

    @Column(nullable = false, length = 4000)
    private String description;

    @Column(name = "estimated_amount", precision = 12, scale = 2)
    private BigDecimal estimatedAmount;

    @Column(name = "approved_amount", precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ClaimStatus status;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "payout_failure_reason", length = 1000)
    private String payoutFailureReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Severity severity;

    @Column(name = "assessment_provider", length = 64)
    private String assessmentProvider;

    @Column(name = "review_assignee", length = 64)
    private String reviewAssignee;

    @Column(name = "review_due_at")
    private Instant reviewDueAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** The policyholder account that submitted the claim (null for claims created before accounts existed). */
    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    @Version
    private long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() {
        // JPA
    }

    private Claim(
            UUID id,
            String claimNumber,
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount,
            UUID ownerId) {
        this.id = id;
        this.ownerId = ownerId;
        this.claimNumber = claimNumber;
        this.policyNumber = policyNumber;
        this.plateNumber = plateNumber;
        this.incidentDate = incidentDate;
        this.description = description;
        this.estimatedAmount = estimatedAmount;
        this.status = ClaimStatus.SUBMITTED;
    }

    public static Claim submit(
            String claimNumber,
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount) {
        return submit(claimNumber, policyNumber, plateNumber, incidentDate, description, estimatedAmount, null);
    }

    public static Claim submit(
            String claimNumber,
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount,
            UUID ownerId) {
        if (incidentDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Incident date cannot be in the future");
        }
        if (estimatedAmount != null && estimatedAmount.signum() < 0) {
            throw new IllegalArgumentException("Estimated amount cannot be negative");
        }
        return new Claim(
                UUID.randomUUID(),
                claimNumber,
                policyNumber,
                plateNumber.toUpperCase().replace(" ", ""),
                incidentDate,
                description,
                estimatedAmount,
                ownerId);
    }

    /** Triage result arrived (from assessment-service or the in-process fallback): the claim is ready for a human. */
    public void completeAssessment(Severity severity, BigDecimal assessedAmount, String provider, Instant reviewDueAt) {
        transitionTo(ClaimStatus.PENDING_REVIEW);
        this.severity = severity;
        this.assessmentProvider = provider;
        this.reviewDueAt = reviewDueAt;
        if (assessedAmount != null) {
            this.estimatedAmount = assessedAmount;
        }
    }

    public void claimReview(String assignee) {
        requireStatus(ClaimStatus.PENDING_REVIEW);
        if (reviewAssignee != null && !reviewAssignee.equals(assignee)) {
            throw new IllegalStateException("Review already claimed by " + reviewAssignee);
        }
        this.reviewAssignee = assignee;
    }

    public void unclaimReview() {
        requireStatus(ClaimStatus.PENDING_REVIEW);
        this.reviewAssignee = null;
    }

    /** @return true if this call escalated the claim (false when already escalated) */
    public boolean escalateReview(Instant now) {
        requireStatus(ClaimStatus.PENDING_REVIEW);
        if (escalatedAt != null) {
            return false;
        }
        this.escalatedAt = now;
        return true;
    }

    public void approve(BigDecimal approvedAmount) {
        if (approvedAmount == null || approvedAmount.signum() <= 0) {
            throw new IllegalArgumentException("Approved amount must be positive");
        }
        transitionTo(ClaimStatus.APPROVED);
        this.approvedAmount = approvedAmount;
        this.payoutFailureReason = null;
    }

    /** After a failed payout the adjuster can retry, optionally with a corrected amount. */
    public void retryPayout(BigDecimal correctedAmount) {
        if (correctedAmount != null && correctedAmount.signum() <= 0) {
            throw new IllegalArgumentException("Approved amount must be positive");
        }
        requireStatus(ClaimStatus.PAYOUT_FAILED);
        transitionTo(ClaimStatus.APPROVED);
        if (correctedAmount != null) {
            this.approvedAmount = correctedAmount;
        }
        this.payoutFailureReason = null;
    }

    public void reject(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }
        transitionTo(ClaimStatus.REJECTED);
        this.rejectionReason = reason;
    }

    public void markPaid() {
        transitionTo(ClaimStatus.PAID);
    }

    public void markPayoutFailed(String reason) {
        transitionTo(ClaimStatus.PAYOUT_FAILED);
        this.payoutFailureReason = reason;
    }

    public void withdraw() {
        transitionTo(ClaimStatus.WITHDRAWN);
    }

    private void requireStatus(ClaimStatus expected) {
        if (status != expected) {
            throw new InvalidStateTransitionException(status, expected);
        }
    }

    private void transitionTo(ClaimStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
    }

    public UUID getId() {
        return id;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public LocalDate getIncidentDate() {
        return incidentDate;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getEstimatedAmount() {
        return estimatedAmount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getPayoutFailureReason() {
        return payoutFailureReason;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getAssessmentProvider() {
        return assessmentProvider;
    }

    public String getReviewAssignee() {
        return reviewAssignee;
    }

    public Instant getReviewDueAt() {
        return reviewDueAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId != null && ownerId.equals(userId);
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
