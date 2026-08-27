package com.kmultan.claims.application;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kmultan.claims.application.assessment.AssessmentProvider;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimStatus;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Time-based reactions that a process engine used to own:
 * <ul>
 *   <li>review SLA breached → REVIEW_SLA_BREACHED (non-blocking, once per claim)</li>
 *   <li>no triage result in time → in-process heuristic completes the assessment</li>
 * </ul>
 * Each claim is handled in its own transaction so one failure does not stall the sweep.
 */
@Component
public class ClaimTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClaimTimeoutScheduler.class);

    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final AssessmentProvider fallbackAssessment;
    private final Duration assessmentTimeout;

    public ClaimTimeoutScheduler(
            ClaimRepository claims,
            ClaimService claimService,
            AssessmentProvider fallbackAssessment,
            @Value("${claims.assessment.timeout}") Duration assessmentTimeout) {
        this.claims = claims;
        this.claimService = claimService;
        this.fallbackAssessment = fallbackAssessment;
        this.assessmentTimeout = assessmentTimeout;
    }

    @Scheduled(fixedDelayString = "${claims.scheduler.interval-ms}")
    @SchedulerLock(name = "claim-timeouts", lockAtLeastFor = "PT5S", lockAtMostFor = "PT2M")
    public void tick() {
        Instant now = Instant.now();
        escalateOverdueReviews(now);
        completeStalledAssessments(now);
    }

    public int escalateOverdueReviews(Instant now) {
        int escalated = 0;
        for (Claim claim :
                claims.findByStatusAndReviewDueAtBeforeAndEscalatedAtIsNull(ClaimStatus.PENDING_REVIEW, now)) {
            try {
                if (claimService.escalateReview(claim.getId(), now)) {
                    escalated++;
                    log.warn(
                            "Review SLA breached for claim {} (due {})",
                            claim.getClaimNumber(),
                            claim.getReviewDueAt());
                }
            } catch (RuntimeException exception) {
                log.error("Escalation failed for claim {}: {}", claim.getId(), exception.toString());
            }
        }
        return escalated;
    }

    public int completeStalledAssessments(Instant now) {
        int completed = 0;
        for (Claim claim : claims.findByStatusAndCreatedAtBefore(ClaimStatus.SUBMITTED, now.minus(assessmentTimeout))) {
            try {
                claimService.completeAssessment(claim.getId(), fallbackAssessment.assess(claim));
                completed++;
                log.warn(
                        "No triage result for claim {} within {}; heuristic fallback applied",
                        claim.getClaimNumber(),
                        assessmentTimeout);
            } catch (RuntimeException exception) {
                log.error("Fallback assessment failed for claim {}: {}", claim.getId(), exception.toString());
            }
        }
        return completed;
    }
}
