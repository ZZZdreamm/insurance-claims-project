package com.kmultan.claims.infrastructure.camunda;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.application.payout.PayoutCommand;
import com.kmultan.claims.infrastructure.payout.FakePayoutParticipant;
import com.kmultan.claims.application.workflow.ClaimWorkflow;
import com.kmultan.claims.application.workflow.ReviewDecision;
import com.kmultan.claims.application.workflow.ReviewDecision.Decision;
import com.kmultan.claims.application.workflow.ReviewTask;
import com.kmultan.claims.application.workflow.ReviewTaskNotFoundException;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Drives the BPMN process end to end on the real engine + real Postgres, with
 * the job executor running: submit → automated assessment → human review → outcome.
 */
class ClaimProcessIT extends AbstractIntegrationTest {

    @Autowired ClaimService claims;
    @Autowired ClaimWorkflow workflow;
    @Autowired RuntimeService runtime;
    @Autowired ManagementService management;
    @Autowired FakePayoutParticipant payout;

    private Claim submit(String description, String amount) {
        return claims.submit("POL-BPMN", "BP 1", LocalDate.now(), description, new BigDecimal(amount));
    }

    private ReviewTask awaitReviewTask(UUID claimId) {
        ReviewTask[] found = new ReviewTask[1];
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            found[0] = workflow.openReviewTasks().stream().filter(t -> t.claimId().equals(claimId)).findFirst().orElse(null);
            assertThat(found[0]).isNotNull();
        });
        return found[0];
    }

    private long runningInstances(UUID claimId) {
        return runtime.createProcessInstanceQuery().processInstanceBusinessKey(claimId.toString()).count();
    }

    @Test
    void approvalPath() {
        Claim claim = submit("Cracked windscreen from a stone on the motorway", "900");

        ReviewTask task = awaitReviewTask(claim.getId());
        assertThat(claims.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        assertThat(claims.get(claim.getId()).getEstimatedAmount()).isEqualByComparingTo("1500.00"); // heuristic band floor
        assertThat(task.severity()).isEqualTo("MODERATE");
        assertThat(task.dueAt()).isAfter(task.createdAt());
        assertThat(task.assignee()).isNull();

        workflow.claimTask(task.taskId(), "alice");
        assertThat(awaitReviewTask(claim.getId()).assignee()).isEqualTo("alice");

        workflow.completeReview(task.taskId(), new ReviewDecision(Decision.APPROVE, new BigDecimal("1400"), null));

        // saga: reserve -> payout -> paid
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(claims.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PAID);
            assertThat(runningInstances(claim.getId())).isZero();
        });
        assertThat(claims.get(claim.getId()).getApprovedAmount()).isEqualByComparingTo("1400");
        assertThat(payout.commandsFor(claim.getId())).extracting(PayoutCommand::type)
                .containsExactly(PayoutCommand.Type.RESERVE_FUNDS, PayoutCommand.Type.ISSUE_PAYOUT);
        assertThatThrownBy(() -> workflow.claimTask(task.taskId(), "bob")).isInstanceOf(ReviewTaskNotFoundException.class);
    }

    @Test
    void payoutFailureCompensatesTheReservation() {
        Claim claim = submit("Bonnet and headlight damage from a deer", "2000");
        ReviewTask task = awaitReviewTask(claim.getId());

        workflow.completeReview(task.taskId(), new ReviewDecision(Decision.APPROVE, new BigDecimal("1500.99"), null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(claims.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PAYOUT_FAILED);
            assertThat(runningInstances(claim.getId())).isZero();
        });
        assertThat(claims.get(claim.getId()).getPayoutFailureReason()).isEqualTo("Payment provider rejected the transfer");
        // reservation succeeded, payout failed -> only the reservation is compensated
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(payout.commandsFor(claim.getId())).extracting(PayoutCommand::type)
                        .containsExactly(PayoutCommand.Type.RESERVE_FUNDS, PayoutCommand.Type.ISSUE_PAYOUT, PayoutCommand.Type.RELEASE_FUNDS));
    }

    @Test
    void rejectedReservationFailsWithoutCompensation() {
        Claim claim = submit("Total loss after a motorway pile-up", "70000");
        ReviewTask task = awaitReviewTask(claim.getId());

        workflow.completeReview(task.taskId(), new ReviewDecision(Decision.APPROVE, new BigDecimal("60000"), null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(claims.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PAYOUT_FAILED);
            assertThat(runningInstances(claim.getId())).isZero();
        });
        assertThat(claims.get(claim.getId()).getPayoutFailureReason()).isEqualTo("Amount exceeds reserve limit");
        // the reserve leg never completed, so nothing to compensate: no RELEASE_FUNDS must be sent
        assertThat(payout.commandsFor(claim.getId())).extracting(PayoutCommand::type)
                .containsExactly(PayoutCommand.Type.RESERVE_FUNDS);
    }

    @Test
    void rejectionPath() {
        Claim claim = submit("Small scratch on the door handle", "150");
        ReviewTask task = awaitReviewTask(claim.getId());

        workflow.completeReview(task.taskId(), new ReviewDecision(Decision.REJECT, null, "Below excess"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(claims.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.REJECTED);
            assertThat(runningInstances(claim.getId())).isZero();
        });
        assertThat(claims.get(claim.getId()).getRejectionReason()).isEqualTo("Below excess");
    }

    @Test
    void slaTimerEscalatesWithoutCancellingReview() {
        Claim claim = submit("Engine bay fire after a collision", "20000");
        ReviewTask task = awaitReviewTask(claim.getId());
        assertThat(task.severity()).isEqualTo("SEVERE");
        assertThat(task.escalated()).isFalse();

        // fire the 48h timer now instead of waiting for it
        String instanceId = runtime.createProcessInstanceQuery().processInstanceBusinessKey(claim.getId().toString()).singleResult().getId();
        Job timer = management.createJobQuery().processInstanceId(instanceId).timers().singleResult();
        assertThat(timer).isNotNull();
        management.executeJob(timer.getId());

        ReviewTask after = awaitReviewTask(claim.getId());
        assertThat(after.taskId()).isEqualTo(task.taskId());     // non-interrupting: same task still open
        assertThat(after.escalated()).isTrue();
        assertThat(claims.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        assertThat(runningInstances(claim.getId())).isEqualTo(1);
    }

    @Test
    void invalidDecisionIsRejectedBeforeTouchingTheEngine() {
        assertThatThrownBy(() -> new ReviewDecision(Decision.APPROVE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewDecision(Decision.REJECT, null, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workflow.completeReview("nope", new ReviewDecision(Decision.REJECT, null, "x")))
                .isInstanceOf(ReviewTaskNotFoundException.class);
    }
}
