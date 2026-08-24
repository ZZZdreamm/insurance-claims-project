package com.kmultan.payout.application;

import java.time.Instant;
import java.util.UUID;

public record PayoutEvent(UUID eventId, Type type, UUID claimId, UUID commandId, String reference, String reason, Instant occurredAt) {
    public enum Type { FUNDS_RESERVED, RESERVATION_REJECTED, PAYOUT_ISSUED, PAYOUT_FAILED, FUNDS_RELEASED, PAYOUT_REVERSED }

    public static PayoutEvent reply(Type type, PayoutCommand cmd, String reference, String reason) {
        return new PayoutEvent(UUID.randomUUID(), type, cmd.claimId(), cmd.commandId(), reference, reason, Instant.now());
    }
}
