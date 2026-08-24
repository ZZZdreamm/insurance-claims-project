package com.kmultan.payout.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** This service's copy of the command contract (see README: no shared kernel between services). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PayoutCommand(UUID commandId, Type type, UUID claimId, String claimNumber, String policyNumber,
                            BigDecimal amount, Instant issuedAt) {
    public enum Type { RESERVE_FUNDS, ISSUE_PAYOUT, RELEASE_FUNDS, REVERSE_PAYOUT }
}
