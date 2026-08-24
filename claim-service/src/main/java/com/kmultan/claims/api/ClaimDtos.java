package com.kmultan.claims.api;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
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

    public record AssessmentResult(@DecimalMin("0.00") BigDecimal assessedAmount) {}

    public record ApproveRequest(@NotNull @Positive BigDecimal approvedAmount) {}

    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}

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
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static ClaimResponse from(Claim c) {
            return new ClaimResponse(c.getId(), c.getClaimNumber(), c.getPolicyNumber(), c.getPlateNumber(),
                    c.getIncidentDate(), c.getDescription(), c.getEstimatedAmount(), c.getApprovedAmount(),
                    c.getStatus(), c.getRejectionReason(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }
}
