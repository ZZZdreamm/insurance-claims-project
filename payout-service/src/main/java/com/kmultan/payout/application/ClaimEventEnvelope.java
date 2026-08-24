package com.kmultan.payout.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * payout-service's view of a claim event. Only the fields this service needs;
 * everything else is ignored so claim-service can evolve the event freely.
 * The shape is pinned by the Pact contract in {@code contracts/pacts}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimEventEnvelope(UUID eventId, String eventType, UUID claimId, Instant occurredAt, Snapshot claim) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(String claimNumber, String policyNumber, BigDecimal approvedAmount) {}

    public static final String CLAIM_APPROVED = "CLAIM_APPROVED";
    public static final String PAYOUT_UNACCEPTED = "PAYOUT_UNACCEPTED";
}
