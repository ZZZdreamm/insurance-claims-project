package com.kmultan.claims.domain.event;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable business fact about a claim. Carries a full snapshot so consumers
 * (search projection, payout, notifications) never need to call back into
 * this service to build their own view.
 */
public record ClaimEvent(
        UUID eventId,
        ClaimEventType eventType,
        UUID claimId,
        Instant occurredAt,
        ClaimSnapshot claim
) {
    public record ClaimSnapshot(
            String claimNumber,
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount,
            BigDecimal approvedAmount,
            ClaimStatus status,
            String rejectionReason
    ) {
        public static ClaimSnapshot of(Claim c) {
            return new ClaimSnapshot(c.getClaimNumber(), c.getPolicyNumber(), c.getPlateNumber(),
                    c.getIncidentDate(), c.getDescription(), c.getEstimatedAmount(), c.getApprovedAmount(),
                    c.getStatus(), c.getRejectionReason());
        }
    }

    public static ClaimEvent of(ClaimEventType type, Claim claim) {
        return new ClaimEvent(UUID.randomUUID(), type, claim.getId(), Instant.now(), ClaimSnapshot.of(claim));
    }
}
