package com.kmultan.claims.application.payout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Command sent to payout-service over Kafka ({@code payout.commands}, keyed by claim id).
 * Commands are imperative ("do this"), unlike claim events, which are facts.
 */
public record PayoutCommand(
        UUID commandId,
        Type type,
        UUID claimId,
        String claimNumber,
        String policyNumber,
        BigDecimal amount,
        Instant issuedAt
) {
    public enum Type { RESERVE_FUNDS, ISSUE_PAYOUT, RELEASE_FUNDS, REVERSE_PAYOUT }

    public static PayoutCommand of(Type type, UUID claimId, String claimNumber, String policyNumber, BigDecimal amount) {
        return new PayoutCommand(UUID.randomUUID(), type, claimId, claimNumber, policyNumber, amount, Instant.now());
    }
}
