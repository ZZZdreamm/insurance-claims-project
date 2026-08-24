package com.kmultan.claims.application.payout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** Reply from payout-service ({@code payout.events}). {@code commandId} ties it to the command it answers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PayoutReply(
        UUID eventId,
        Type type,
        UUID claimId,
        UUID commandId,
        String reference,
        String reason,
        Instant occurredAt
) {
    public enum Type { FUNDS_RESERVED, RESERVATION_REJECTED, PAYOUT_ISSUED, PAYOUT_FAILED, FUNDS_RELEASED, PAYOUT_REVERSED }
}
