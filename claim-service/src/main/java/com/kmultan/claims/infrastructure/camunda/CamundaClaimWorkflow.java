package com.kmultan.claims.infrastructure.camunda;

import com.kmultan.claims.application.payout.PayoutReply;
import com.kmultan.claims.application.workflow.ClaimWorkflow;
import com.kmultan.claims.application.workflow.ReviewDecision;
import com.kmultan.claims.application.workflow.ReviewTask;
import com.kmultan.claims.application.workflow.ReviewTaskNotFoundException;
import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.kmultan.claims.infrastructure.camunda.ProcessVariables.*;

@Component
public class CamundaClaimWorkflow implements ClaimWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CamundaClaimWorkflow.class);

    private final RuntimeService runtime;
    private final TaskService tasks;
    private final String slaDuration;
    private final String payoutTimeout;

    public CamundaClaimWorkflow(RuntimeService runtime, TaskService tasks,
                                @Value("${claims.process.sla-duration}") String slaDuration,
                                @Value("${claims.process.payout-timeout}") String payoutTimeout) {
        this.runtime = runtime;
        this.tasks = tasks;
        this.slaDuration = slaDuration;
        this.payoutTimeout = payoutTimeout;
    }

    @Override
    public void start(UUID claimId) {
        // business key = claim id, so every delegate and query can find the claim without extra variables
        runtime.startProcessInstanceByKey(PROCESS_KEY, claimId.toString(),
                Map.of(SLA_DURATION, slaDuration, PAYOUT_TIMEOUT, payoutTimeout));
    }

    @Override
    public void onPayoutReply(PayoutReply reply) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(REPLY_TYPE, reply.type().name());
        vars.put(REPLY_REASON, reply.reason());
        if (reply.reference() != null) {
            vars.put(PAYOUT_REFERENCE, reply.reference());
        }
        try {
            // matching on the pending command id guards against a late reply for a previous step
            runtime.createMessageCorrelation(PAYOUT_REPLY_MESSAGE)
                    .processInstanceBusinessKey(reply.claimId().toString())
                    .processInstanceVariableEquals(PENDING_COMMAND_ID, reply.commandId().toString())
                    .setVariables(vars)
                    .correlate();
        } catch (MismatchingMessageCorrelationException e) {
            // nothing is waiting: duplicate delivery, compensation acknowledgement, or a reply that timed out
            log.info("Ignoring payout reply {} for claim {} (command {}): no waiting step", reply.type(), reply.claimId(), reply.commandId());
        }
    }

    @Override
    public List<ReviewTask> openReviewTasks() {
        return tasks.createTaskQuery()
                .processDefinitionKey(PROCESS_KEY)
                .taskDefinitionKey(REVIEW_TASK)
                .active()
                .orderByTaskCreateTime().asc()
                .list().stream()
                .map(this::toReviewTask)
                .toList();
    }

    @Override
    public void claimTask(String taskId, String assignee) {
        tasks.setAssignee(requireTask(taskId).getId(), assignee);
    }

    @Override
    public void unclaimTask(String taskId) {
        tasks.setAssignee(requireTask(taskId).getId(), null);
    }

    @Override
    public void completeReview(String taskId, ReviewDecision decision) {
        Task task = requireTask(taskId);
        Map<String, Object> vars = new HashMap<>();
        vars.put(DECISION, decision.decision().name());
        if (decision.approvedAmount() != null) {
            vars.put(APPROVED_AMOUNT, decision.approvedAmount().toPlainString());
        }
        if (decision.reason() != null) {
            vars.put(REJECTION_REASON, decision.reason());
        }
        tasks.complete(task.getId(), vars);
    }

    private Task requireTask(String taskId) {
        Task task = tasks.createTaskQuery().taskId(taskId).taskDefinitionKey(REVIEW_TASK).active().singleResult();
        if (task == null) {
            throw new ReviewTaskNotFoundException(taskId);
        }
        return task;
    }

    private ReviewTask toReviewTask(Task task) {
        Map<String, Object> vars = runtime.getVariables(task.getProcessInstanceId(), List.of(SEVERITY, ESCALATED));
        return new ReviewTask(
                task.getId(),
                UUID.fromString(runtime.createProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult().getBusinessKey()),
                task.getAssignee(),
                task.getCreateTime().toInstant(),
                task.getDueDate() == null ? null : task.getDueDate().toInstant(),
                (String) vars.get(SEVERITY),
                Boolean.TRUE.equals(vars.get(ESCALATED)));
    }
}
