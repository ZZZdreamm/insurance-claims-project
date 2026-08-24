package com.kmultan.claims.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Claim lifecycle. Transitions are defined here so the aggregate, not a
 * controller or a message handler, is the single authority on what is legal.
 *
 * SUBMITTED -> PENDING_REVIEW -> APPROVED -> PAID
 *                             -> REJECTED   APPROVED -> PAYOUT_FAILED -> APPROVED (retry)
 * Any non-terminal state -> WITHDRAWN
 */
public enum ClaimStatus {
    SUBMITTED,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    PAID,
    PAYOUT_FAILED,
    WITHDRAWN;

    public Set<ClaimStatus> allowedTransitions() {
        return switch (this) {
            case SUBMITTED -> EnumSet.of(PENDING_REVIEW, WITHDRAWN);
            case PENDING_REVIEW -> EnumSet.of(APPROVED, REJECTED, WITHDRAWN);
            case APPROVED -> EnumSet.of(PAID, PAYOUT_FAILED, WITHDRAWN);
            case PAYOUT_FAILED -> EnumSet.of(APPROVED, WITHDRAWN);
            case REJECTED, PAID, WITHDRAWN -> EnumSet.noneOf(ClaimStatus.class);
        };
    }

    public boolean canTransitionTo(ClaimStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
