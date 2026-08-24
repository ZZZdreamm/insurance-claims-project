package com.kmultan.claims.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Claim lifecycle. Transitions are defined here so the aggregate, not the
 * controller or the process engine, is the single authority on what is legal.
 *
 * SUBMITTED -> UNDER_ASSESSMENT -> PENDING_REVIEW -> APPROVED -> PAID
 *                                                             -> PAYOUT_FAILED (saga compensated)
 *                                                 -> REJECTED
 * Any non-terminal state -> WITHDRAWN
 */
public enum ClaimStatus {
    SUBMITTED,
    UNDER_ASSESSMENT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    PAID,
    PAYOUT_FAILED,
    WITHDRAWN;

    public Set<ClaimStatus> allowedTransitions() {
        return switch (this) {
            case SUBMITTED -> EnumSet.of(UNDER_ASSESSMENT, WITHDRAWN);
            case UNDER_ASSESSMENT -> EnumSet.of(PENDING_REVIEW, WITHDRAWN);
            case PENDING_REVIEW -> EnumSet.of(APPROVED, REJECTED, WITHDRAWN);
            case APPROVED -> EnumSet.of(PAID, PAYOUT_FAILED, WITHDRAWN);
            case REJECTED, PAID, PAYOUT_FAILED, WITHDRAWN -> EnumSet.noneOf(ClaimStatus.class);
        };
    }

    public boolean canTransitionTo(ClaimStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
