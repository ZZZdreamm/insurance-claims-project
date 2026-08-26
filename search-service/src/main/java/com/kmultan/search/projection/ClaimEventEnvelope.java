package com.kmultan.search.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * This service's own view of the claim event contract. Deliberately not a
 * shared library: each consumer parses only the fields it needs and ignores
 * the rest, so the producer can add fields without a lock-step deploy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimEventEnvelope(UUID eventId, String eventType, UUID claimId, Instant occurredAt, Snapshot claim) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(
            String claimNumber,
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount,
            BigDecimal approvedAmount,
            String status,
            String rejectionReason) {}
}
