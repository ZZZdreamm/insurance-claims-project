package com.kmultan.claims.infrastructure.consumers;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** claim-service's view of payout-service's events. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PayoutEvent(
        UUID eventId,
        String type,
        UUID claimId,
        UUID causationEventId,
        String reference,
        String reason,
        Instant occurredAt) {
    public static final String PAYOUT_ISSUED = "PAYOUT_ISSUED";
    public static final String PAYOUT_FAILED = "PAYOUT_FAILED";
    public static final String RESERVATION_REJECTED = "RESERVATION_REJECTED";
}
