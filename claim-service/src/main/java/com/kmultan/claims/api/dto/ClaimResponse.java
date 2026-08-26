package com.kmultan.claims.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Severity;

public record ClaimResponse(
        UUID id,
        String claimNumber,
        String policyNumber,
        String plateNumber,
        LocalDate incidentDate,
        String description,
        BigDecimal estimatedAmount,
        BigDecimal approvedAmount,
        ClaimStatus status,
        String rejectionReason,
        String payoutFailureReason,
        Severity severity,
        String assessmentProvider,
        BigDecimal assessmentScore,
        String assessmentExplanation,
        Instant assessedAt,
        Instant paidAt,
        String payoutReference,
        UUID ownerId,
        String reviewAssignee,
        Instant reviewDueAt,
        boolean escalated,
        List<UUID> photoIds,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ClaimResponse from(Claim claim, List<UUID> photoIds) {
        return new ClaimResponse(
                claim.getId(),
                claim.getClaimNumber(),
                claim.getPolicyNumber(),
                claim.getPlateNumber(),
                claim.getIncidentDate(),
                claim.getDescription(),
                claim.getEstimatedAmount(),
                claim.getApprovedAmount(),
                claim.getStatus(),
                claim.getRejectionReason(),
                claim.getPayoutFailureReason(),
                claim.getSeverity(),
                claim.getAssessmentProvider(),
                claim.getAssessmentScore(),
                claim.getAssessmentExplanation(),
                claim.getAssessedAt(),
                claim.getPaidAt(),
                claim.getPayoutReference(),
                claim.getOwnerId(),
                claim.getReviewAssignee(),
                claim.getReviewDueAt(),
                claim.getEscalatedAt() != null,
                photoIds,
                claim.getVersion(),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }
}
