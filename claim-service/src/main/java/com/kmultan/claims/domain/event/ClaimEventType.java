package com.kmultan.claims.domain.event;

public enum ClaimEventType {
    CLAIM_SUBMITTED,
    ASSESSMENT_COMPLETED,
    REVIEW_CLAIMED,
    REVIEW_UNCLAIMED,
    REVIEW_SLA_BREACHED,
    /** payable amount exceeds the approver's limit; a second, different approver must confirm */
    SECOND_APPROVAL_REQUESTED,
    CLAIM_APPROVED,
    /** an advance has been paid; the remainder is still to be settled */
    CLAIM_PARTIALLY_PAID,
    CLAIM_REJECTED,
    CLAIM_PAID,
    PAYOUT_FAILED,
    /** payout-service paid, but the claim can no longer accept it (e.g. withdrawn) — payout-service must reverse */
    PAYOUT_UNACCEPTED,
    CLAIM_WITHDRAWN,
    /** a recovery case against the liable third party was opened after payout */
    SUBROGATION_OPENED,
    SUBROGATION_RECOVERY_RECORDED,
    SUBROGATION_CLOSED
}
