package com.kmultan.claims.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Severity;

/**
 * Immutable business fact about a claim. Carries a full snapshot so consumers
 * (search projection, payout, notifications) never need to call back into
 * this service to build their own view.
 */
public record ClaimEvent(
        UUID eventId, ClaimEventType eventType, UUID claimId, Instant occurredAt, ClaimSnapshot claim) {
    public record ClaimSnapshot(
            String claimNumber,
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount,
            BigDecimal approvedAmount,
            BigDecimal payableAmount,
            BigDecimal paidAmount,
            BigDecimal deductibleApplied,
            boolean fraudSuspected,
            ClaimStatus status,
            String rejectionReason,
            Severity severity,
            String reviewAssignee,
            Instant reviewDueAt,
            boolean escalated,
            List<UUID> photoIds) {
        public static ClaimSnapshot of(Claim claim, List<UUID> photoIds) {
            return new ClaimSnapshot(
                    claim.getClaimNumber(),
                    claim.getPolicyNumber(),
                    claim.getPlateNumber(),
                    claim.getIncidentDate(),
                    claim.getDescription(),
                    claim.getEstimatedAmount(),
                    claim.getApprovedAmount(),
                    claim.getPayableAmount(),
                    claim.getPaidAmount(),
                    claim.getDeductibleApplied(),
                    claim.isFraudSuspected(),
                    claim.getStatus(),
                    claim.getRejectionReason(),
                    claim.getSeverity(),
                    claim.getReviewAssignee(),
                    claim.getReviewDueAt(),
                    claim.getEscalatedAt() != null,
                    photoIds);
        }
    }

    public static ClaimEvent of(ClaimEventType type, Claim claim, List<UUID> photoIds) {
        return new ClaimEvent(UUID.randomUUID(), type, claim.getId(), Instant.now(), ClaimSnapshot.of(claim, photoIds));
    }
}
