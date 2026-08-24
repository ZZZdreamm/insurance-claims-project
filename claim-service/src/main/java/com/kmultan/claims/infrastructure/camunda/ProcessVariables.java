package com.kmultan.claims.infrastructure.camunda;

/** Names shared between the BPMN file, delegates and the workflow adapter. */
final class ProcessVariables {
    static final String PROCESS_KEY = "claim-handling";
    static final String REVIEW_TASK = "adjusterReview";
    static final String CANDIDATE_GROUP = "adjusters";

    static final String SLA_DURATION = "slaDuration";
    static final String SEVERITY = "severity";
    static final String ASSESSED_AMOUNT = "assessedAmount";
    static final String DECISION = "decision";
    static final String APPROVED_AMOUNT = "approvedAmount";
    static final String REJECTION_REASON = "rejectionReason";
    static final String ESCALATED = "escalated";

    static final String PAYOUT_REPLY_MESSAGE = "PayoutReply";
    static final String PENDING_COMMAND_ID = "pendingCommandId";
    static final String REPLY_TYPE = "replyType";
    static final String REPLY_REASON = "replyReason";
    static final String PAYOUT_REFERENCE = "payoutReference";
    static final String PAYOUT_TIMEOUT = "payoutTimeout";

    private ProcessVariables() {}
}
