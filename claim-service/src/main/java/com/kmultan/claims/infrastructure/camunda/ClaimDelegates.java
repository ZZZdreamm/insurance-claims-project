package com.kmultan.claims.infrastructure.camunda;

import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.application.assessment.AssessmentProvider;
import com.kmultan.claims.application.payout.PayoutCommand;
import com.kmultan.claims.application.payout.PayoutCommandSender;
import com.kmultan.claims.domain.Claim;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import static com.kmultan.claims.infrastructure.camunda.ProcessVariables.*;

/**
 * Service-task delegates. Each one is a thin bridge: read the claim id from the
 * business key, call the application service, write back the variables the
 * process needs for routing. No business rules live here.
 *
 * They run inside the job executor's transaction (Spring-managed), so a failing
 * delegate rolls back the claim change and Camunda retries the job.
 */
@Configuration
public class ClaimDelegates {

    private static final Logger log = LoggerFactory.getLogger(ClaimDelegates.class);

    private static UUID claimId(DelegateExecution execution) {
        return UUID.fromString(execution.getProcessBusinessKey());
    }

    @Bean
    JavaDelegate startAssessmentDelegate(ClaimService claims) {
        return execution -> claims.startAssessment(claimId(execution));
    }

    @Bean
    JavaDelegate runAssessmentDelegate(ClaimService claims, AssessmentProvider provider) {
        return execution -> {
            UUID id = claimId(execution);
            Assessment a = provider.assess(claims.get(id));
            claims.completeAssessment(id, a.assessedAmount());
            execution.setVariable(SEVERITY, a.severity().name());
            execution.setVariable(ASSESSED_AMOUNT, a.assessedAmount().toPlainString());
            log.info("Claim {} assessed as {} ({}) by {}", id, a.severity(), a.assessedAmount(), a.provider());
        };
    }

    @Bean
    JavaDelegate approveClaimDelegate(ClaimService claims) {
        return execution -> claims.approve(claimId(execution),
                new BigDecimal((String) execution.getVariable(APPROVED_AMOUNT)));
    }

    @Bean
    JavaDelegate rejectClaimDelegate(ClaimService claims) {
        return execution -> claims.reject(claimId(execution), (String) execution.getVariable(REJECTION_REASON));
    }

    @Bean
    JavaDelegate escalateSlaDelegate() {
        return execution -> {
            // Phase 5 wires this to a RabbitMQ notification job; for now it is a flag + a log line
            execution.setVariable(ESCALATED, true);
            log.warn("SLA breached for claim {} — review still open", claimId(execution));
        };
    }

    // ---- payout saga legs: each sends a command and records which reply the process is waiting for ----

    private static JavaDelegate sendCommand(ClaimService claims, PayoutCommandSender sender, PayoutCommand.Type type) {
        return execution -> {
            Claim claim = claims.get(claimId(execution));
            PayoutCommand cmd = PayoutCommand.of(type, claim.getId(), claim.getClaimNumber(), claim.getPolicyNumber(), claim.getApprovedAmount());
            sender.send(cmd);
            execution.setVariable(PENDING_COMMAND_ID, cmd.commandId().toString());
            log.info("Sent {} for claim {} ({})", type, claim.getId(), claim.getApprovedAmount());
        };
    }

    @Bean JavaDelegate sendReserveFundsDelegate(ClaimService c, PayoutCommandSender s) { return sendCommand(c, s, PayoutCommand.Type.RESERVE_FUNDS); }
    @Bean JavaDelegate sendIssuePayoutDelegate(ClaimService c, PayoutCommandSender s) { return sendCommand(c, s, PayoutCommand.Type.ISSUE_PAYOUT); }
    /** Compensation handlers: fire-and-forget, at-least-once via the outbox; payout-service applies them idempotently. */
    @Bean JavaDelegate sendReleaseFundsDelegate(ClaimService c, PayoutCommandSender s) { return sendCommand(c, s, PayoutCommand.Type.RELEASE_FUNDS); }
    @Bean JavaDelegate sendReversePayoutDelegate(ClaimService c, PayoutCommandSender s) { return sendCommand(c, s, PayoutCommand.Type.REVERSE_PAYOUT); }

    @Bean
    JavaDelegate markClaimPaidDelegate(ClaimService claims) {
        return execution -> claims.markPaid(claimId(execution));
    }

    @Bean
    JavaDelegate markPayoutFailedDelegate(ClaimService claims) {
        return execution -> {
            String reason = (String) execution.getVariable(REPLY_REASON);
            claims.markPayoutFailed(claimId(execution), reason == null ? "Payout step timed out" : reason);
        };
    }

    /** Sets the task due date from the SLA so the console can show time remaining. */
    @Bean
    TaskListener reviewTaskCreateListener() {
        return (DelegateTask task) -> {
            String sla = (String) task.getVariable(SLA_DURATION);
            task.setDueDate(Date.from(task.getCreateTime().toInstant().plus(Duration.parse(sla))));
        };
    }
}
