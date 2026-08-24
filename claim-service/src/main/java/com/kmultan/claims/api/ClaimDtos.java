package com.kmultan.claims.api;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Severity;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ClaimDtos {
    private ClaimDtos() {}

    public record SubmitClaimRequest(
            @NotBlank @Size(max = 32) String policyNumber,
            @NotBlank @Size(max = 16)
            @Pattern(regexp = "^[A-Za-z0-9 -]+$", message = "must contain only letters, digits, spaces or dashes")
            String plateNumber,
            @NotNull @PastOrPresent LocalDate incidentDate,
            @NotBlank @Size(min = 10, max = 4000) String description,
            @DecimalMin("0.00") BigDecimal estimatedAmount
    ) {}

    public record ClaimReviewRequest(@NotBlank @Size(max = 64) String assignee) {}
    public record ApproveRequest(@NotNull @Positive BigDecimal approvedAmount) {}
    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}
    public record RetryPayoutRequest(@Positive BigDecimal approvedAmount) {}

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
            String reviewAssignee,
            Instant reviewDueAt,
            boolean escalated,
            List<UUID> photoIds,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static ClaimResponse from(Claim c, List<UUID> photoIds) {
            return new ClaimResponse(c.getId(), c.getClaimNumber(), c.getPolicyNumber(), c.getPlateNumber(),
                    c.getIncidentDate(), c.getDescription(), c.getEstimatedAmount(), c.getApprovedAmount(),
                    c.getStatus(), c.getRejectionReason(), c.getPayoutFailureReason(), c.getSeverity(),
                    c.getAssessmentProvider(), c.getReviewAssignee(), c.getReviewDueAt(), c.getEscalatedAt() != null,
                    photoIds, c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }
}
