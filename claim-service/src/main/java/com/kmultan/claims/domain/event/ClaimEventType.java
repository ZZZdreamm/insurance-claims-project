package com.kmultan.claims.domain.event;

public enum ClaimEventType {
    CLAIM_SUBMITTED,
    ASSESSMENT_COMPLETED,
    REVIEW_CLAIMED,
    REVIEW_UNCLAIMED,
    REVIEW_SLA_BREACHED,
    CLAIM_APPROVED,
    CLAIM_REJECTED,
    CLAIM_PAID,
    PAYOUT_FAILED,
    /** payout-service paid, but the claim can no longer accept it (e.g. withdrawn) — payout-service must reverse */
    PAYOUT_UNACCEPTED,
    CLAIM_WITHDRAWN
}
