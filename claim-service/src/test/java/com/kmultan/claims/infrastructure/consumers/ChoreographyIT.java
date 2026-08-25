package com.kmultan.claims.infrastructure.consumers;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.application.ClaimTimeoutScheduler;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The whole claim lifecycle driven purely by events over a real broker:
 * submit → (assessment-service) → review → approve → (payout-service) → paid,
 * plus every unhappy path and the scheduler's time-based reactions.
 */
class ChoreographyIT extends AbstractIntegrationTest {

    @Autowired ClaimService claimService;
    @Autowired ClaimTimeoutScheduler timeoutScheduler;
    @Autowired FakeDownstreamServices downstreamServices;

    private Claim submit(String description, String estimatedAmount) {
        return claimService.submit("POL-CH", "CH 1", LocalDate.now(), description, new BigDecimal(estimatedAmount), List.of());
    }

    private void awaitStatus(Claim claim, ClaimStatus expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(claimService.get(claim.getId()).getStatus()).isEqualTo(expected));
    }

    @Test
    void happyPath_submitTriageReviewApprovePaid() {
        Claim claim = submit("Cracked windscreen from a stone on the motorway", "900");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);

        awaitStatus(claim, ClaimStatus.PENDING_REVIEW);
        Claim triagedClaim = claimService.get(claim.getId());
        assertThat(triagedClaim.getSeverity()).isEqualTo(Severity.MODERATE);
        assertThat(triagedClaim.getAssessmentProvider()).isEqualTo("fake-assessment/test");
        assertThat(triagedClaim.getEstimatedAmount()).isEqualByComparingTo("1500.00");
        assertThat(triagedClaim.getReviewDueAt()).isAfter(Instant.now().plus(Duration.ofHours(47)));
        assertThat(claimService.openReviews()).extracting(Claim::getId).contains(claim.getId());

        claimService.claimReview(claim.getId(), "alice");
        assertThatThrownBy(() -> claimService.claimReview(claim.getId(), "bob")).isInstanceOf(IllegalStateException.class);
        claimService.approve(claim.getId(), new BigDecimal("1400"));

        awaitStatus(claim, ClaimStatus.PAID);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(downstreamServices.eventTypesFor(claim.getId()))
                        .containsExactly("CLAIM_SUBMITTED", "ASSESSMENT_COMPLETED", "REVIEW_CLAIMED", "CLAIM_APPROVED", "CLAIM_PAID"));
    }

    @Test
    void failedPayoutCanBeRetriedWithCorrectedAmount() {
        Claim claim = submit("Bonnet and headlight damage from a deer", "2000");
        awaitStatus(claim, ClaimStatus.PENDING_REVIEW);
        claimService.approve(claim.getId(), new BigDecimal("1500.99"));

        awaitStatus(claim, ClaimStatus.PAYOUT_FAILED);
        assertThat(claimService.get(claim.getId()).getPayoutFailureReason()).isEqualTo("Payment provider rejected the transfer");

        claimService.retryPayout(claim.getId(), new BigDecimal("1501.00"));
        assertThat(claimService.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.APPROVED);
        awaitStatus(claim, ClaimStatus.PAID);
        assertThat(claimService.get(claim.getId()).getApprovedAmount()).isEqualByComparingTo("1501.00");
        assertThat(claimService.get(claim.getId()).getPayoutFailureReason()).isNull();
    }

    @Test
    void rejectedReservationFailsThePayout() {
        Claim claim = submit("Total loss after a motorway pile-up", "70000");
        awaitStatus(claim, ClaimStatus.PENDING_REVIEW);
        claimService.approve(claim.getId(), new BigDecimal("60000"));
        awaitStatus(claim, ClaimStatus.PAYOUT_FAILED);
        assertThat(claimService.get(claim.getId()).getPayoutFailureReason()).isEqualTo("Amount exceeds reserve limit");
    }

    @Test
    void payoutForWithdrawnClaimIsRefusedAndReversed() {
        Claim claim = submit("Door dented in a car park", "700");
        awaitStatus(claim, ClaimStatus.PENDING_REVIEW);
        // approve then withdraw immediately: payout-service will still pay (it reacted to CLAIM_APPROVED)
        claimService.approve(claim.getId(), new BigDecimal("650"));
        claimService.withdraw(claim.getId());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(downstreamServices.eventTypesFor(claim.getId())).contains("PAYOUT_UNACCEPTED"));
        assertThat(claimService.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
    }

    @Test
    void reviewSlaBreachIsEscalatedOnceWithoutBlockingTheReview() {
        Claim claim = submit("Engine bay fire after a collision", "20000");
        awaitStatus(claim, ClaimStatus.PENDING_REVIEW);
        assertThat(claimService.get(claim.getId()).getSeverity()).isEqualTo(Severity.SEVERE);

        Instant afterSla = Instant.now().plus(Duration.ofHours(49));
        assertThat(timeoutScheduler.escalateOverdueReviews(afterSla)).isGreaterThanOrEqualTo(1);
        assertThat(timeoutScheduler.escalateOverdueReviews(afterSla)).isZero();   // once per claim

        Claim escalatedClaim = claimService.get(claim.getId());
        assertThat(escalatedClaim.getEscalatedAt()).isNotNull();
        assertThat(escalatedClaim.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(downstreamServices.eventTypesFor(claim.getId())).containsOnlyOnce("REVIEW_SLA_BREACHED"));
    }

    @Test
    void missingTriageResultFallsBackToHeuristicAfterTimeout() {
        Claim claim = submit("NOASSESS scratched bumper in a car park", "400");
        Instant afterTimeout = Instant.now().plus(Duration.ofMinutes(5));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(timeoutScheduler.completeStalledAssessments(afterTimeout)).isGreaterThanOrEqualTo(1));

        Claim triagedClaim = claimService.get(claim.getId());
        assertThat(triagedClaim.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        assertThat(triagedClaim.getSeverity()).isEqualTo(Severity.MINOR);
        assertThat(triagedClaim.getAssessmentProvider()).isEqualTo("heuristic-fallback");
    }
}
