package com.kmultan.payout.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Facts published by payout-service on {@code payout.events}, keyed by claim id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PayoutEvent(UUID eventId, Type type, UUID claimId, UUID causationEventId,
                          BigDecimal amount, String reference, String reason, Instant occurredAt) {
    public enum Type { FUNDS_RESERVED, RESERVATION_REJECTED, PAYOUT_ISSUED, PAYOUT_FAILED, FUNDS_RELEASED, PAYOUT_REVERSED }

    public static PayoutEvent of(Type type, UUID claimId, UUID causationEventId, BigDecimal amount, String reference, String reason) {
        return new PayoutEvent(UUID.randomUUID(), type, claimId, causationEventId, amount, reference, reason, Instant.now());
    }
}
