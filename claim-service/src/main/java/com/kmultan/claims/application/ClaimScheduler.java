package com.kmultan.claims.application;

import com.kmultan.claims.application.assessment.AssessmentProvider;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Time-based reactions that a process engine used to own:
 * <ul>
 *   <li>review SLA breached → REVIEW_SLA_BREACHED (non-blocking, once per claim)</li>
 *   <li>no triage result in time → in-process heuristic completes the assessment</li>
 * </ul>
 * Each claim is handled in its own transaction so one failure does not stall the sweep.
 */
@Component
public class ClaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClaimScheduler.class);

    private final ClaimRepository claims;
    private final ClaimService service;
    private final AssessmentProvider fallback;
    private final Duration assessmentTimeout;

    public ClaimScheduler(ClaimRepository claims, ClaimService service, AssessmentProvider fallback,
                          @Value("${claims.assessment.timeout}") Duration assessmentTimeout) {
        this.claims = claims;
        this.service = service;
        this.fallback = fallback;
        this.assessmentTimeout = assessmentTimeout;
    }

    @Scheduled(fixedDelayString = "${claims.scheduler.interval-ms}")
    public void tick() {
        Instant now = Instant.now();
        escalateOverdueReviews(now);
        completeStalledAssessments(now);
    }

    public int escalateOverdueReviews(Instant now) {
        int escalated = 0;
        for (Claim c : claims.findByStatusAndReviewDueAtBeforeAndEscalatedAtIsNull(ClaimStatus.PENDING_REVIEW, now)) {
            try {
                if (service.escalateReview(c.getId(), now)) {
                    escalated++;
                    log.warn("Review SLA breached for claim {} (due {})", c.getClaimNumber(), c.getReviewDueAt());
                }
            } catch (RuntimeException e) {
                log.error("Escalation failed for claim {}: {}", c.getId(), e.toString());
            }
        }
        return escalated;
    }

    public int completeStalledAssessments(Instant now) {
        int completed = 0;
        for (Claim c : claims.findByStatusAndCreatedAtBefore(ClaimStatus.SUBMITTED, now.minus(assessmentTimeout))) {
            try {
                service.completeAssessment(c.getId(), fallback.assess(c));
                completed++;
                log.warn("No triage result for claim {} within {}; heuristic fallback applied", c.getClaimNumber(), assessmentTimeout);
            } catch (RuntimeException e) {
                log.error("Fallback assessment failed for claim {}: {}", c.getId(), e.toString());
            }
        }
        return completed;
    }
}
