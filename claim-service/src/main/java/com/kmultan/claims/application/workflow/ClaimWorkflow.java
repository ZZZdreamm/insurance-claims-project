package com.kmultan.claims.application.workflow;

import com.kmultan.claims.application.payout.PayoutReply;

import java.util.List;
import java.util.UUID;

/**
 * Port to the process engine. The application layer talks in terms of claims
 * and review decisions; only the adapter knows about process instances,
 * variables and BPMN task ids.
 */
public interface ClaimWorkflow {

    void start(UUID claimId);

    List<ReviewTask> openReviewTasks();

    void claimTask(String taskId, String assignee);

    void unclaimTask(String taskId);

    void completeReview(String taskId, ReviewDecision decision);

    /** Deliver a payout-service reply to the saga step waiting for it. Duplicates and late replies are ignored. */
    void onPayoutReply(PayoutReply reply);
}
