package com.kmultan.claims.infrastructure.consumers;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimScheduler;
import com.kmultan.claims.application.ClaimService;
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

    @Autowired ClaimService claims;
    @Autowired ClaimScheduler scheduler;
    @Autowired FakeDownstreamServices downstream;

    private Claim submit(String description, String amount) {
        return claims.submit("POL-CH", "CH 1", LocalDate.now(), description, new BigDecimal(amount), List.of());
    }

    private void awaitStatus(Claim c, ClaimStatus expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(claims.get(c.getId()).getStatus()).isEqualTo(expected));
    }

    @Test
    void happyPath_submitTriageReviewApprovePaid() {
        Claim c = submit("Cracked windscreen from a stone on the motorway", "900");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);

        awaitStatus(c, ClaimStatus.PENDING_REVIEW);
        Claim triaged = claims.get(c.getId());
        assertThat(triaged.getSeverity()).isEqualTo(Severity.MODERATE);
        assertThat(triaged.getAssessmentProvider()).isEqualTo("fake-assessment/test");
        assertThat(triaged.getEstimatedAmount()).isEqualByComparingTo("1500.00");
        assertThat(triaged.getReviewDueAt()).isAfter(Instant.now().plus(Duration.ofHours(47)));
        assertThat(claims.openReviews()).extracting(Claim::getId).contains(c.getId());

        claims.claimReview(c.getId(), "alice");
        assertThatThrownBy(() -> claims.claimReview(c.getId(), "bob")).isInstanceOf(IllegalStateException.class);
        claims.approve(c.getId(), new BigDecimal("1400"));

        awaitStatus(c, ClaimStatus.PAID);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(downstream.eventTypesFor(c.getId()))
                        .containsExactly("CLAIM_SUBMITTED", "ASSESSMENT_COMPLETED", "REVIEW_CLAIMED", "CLAIM_APPROVED", "CLAIM_PAID"));
    }

    @Test
    void failedPayoutCanBeRetriedWithCorrectedAmount() {
        Claim c = submit("Bonnet and headlight damage from a deer", "2000");
        awaitStatus(c, ClaimStatus.PENDING_REVIEW);
        claims.approve(c.getId(), new BigDecimal("1500.99"));

        awaitStatus(c, ClaimStatus.PAYOUT_FAILED);
        assertThat(claims.get(c.getId()).getPayoutFailureReason()).isEqualTo("Payment provider rejected the transfer");

        claims.retryPayout(c.getId(), new BigDecimal("1501.00"));
        assertThat(claims.get(c.getId()).getStatus()).isEqualTo(ClaimStatus.APPROVED);
        awaitStatus(c, ClaimStatus.PAID);
        assertThat(claims.get(c.getId()).getApprovedAmount()).isEqualByComparingTo("1501.00");
        assertThat(claims.get(c.getId()).getPayoutFailureReason()).isNull();
    }

    @Test
    void rejectedReservationFailsThePayout() {
        Claim c = submit("Total loss after a motorway pile-up", "70000");
        awaitStatus(c, ClaimStatus.PENDING_REVIEW);
        claims.approve(c.getId(), new BigDecimal("60000"));
        awaitStatus(c, ClaimStatus.PAYOUT_FAILED);
        assertThat(claims.get(c.getId()).getPayoutFailureReason()).isEqualTo("Amount exceeds reserve limit");
    }

    @Test
    void payoutForWithdrawnClaimIsRefusedAndReversed() {
        Claim c = submit("Door dented in a car park", "700");
        awaitStatus(c, ClaimStatus.PENDING_REVIEW);
        // approve then withdraw immediately: payout-service will still pay (it reacted to CLAIM_APPROVED)
        claims.approve(c.getId(), new BigDecimal("650"));
        claims.withdraw(c.getId());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(downstream.eventTypesFor(c.getId())).contains("PAYOUT_UNACCEPTED"));
        assertThat(claims.get(c.getId()).getStatus()).isEqualTo(ClaimStatus.WITHDRAWN);
    }

    @Test
    void reviewSlaBreachIsEscalatedOnceWithoutBlockingTheReview() {
        Claim c = submit("Engine bay fire after a collision", "20000");
        awaitStatus(c, ClaimStatus.PENDING_REVIEW);
        assertThat(claims.get(c.getId()).getSeverity()).isEqualTo(Severity.SEVERE);

        Instant later = Instant.now().plus(Duration.ofHours(49));
        assertThat(scheduler.escalateOverdueReviews(later)).isGreaterThanOrEqualTo(1);
        assertThat(scheduler.escalateOverdueReviews(later)).isZero();   // once per claim

        Claim escalated = claims.get(c.getId());
        assertThat(escalated.getEscalatedAt()).isNotNull();
        assertThat(escalated.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(downstream.eventTypesFor(c.getId())).containsOnlyOnce("REVIEW_SLA_BREACHED"));
    }

    @Test
    void missingTriageResultFallsBackToHeuristicAfterTimeout() {
        Claim c = submit("NOASSESS scratched bumper in a car park", "400");
        Instant later = Instant.now().plus(Duration.ofMinutes(5));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(scheduler.completeStalledAssessments(later)).isGreaterThanOrEqualTo(1));

        Claim triaged = claims.get(c.getId());
        assertThat(triaged.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        assertThat(triaged.getSeverity()).isEqualTo(Severity.MINOR);
        assertThat(triaged.getAssessmentProvider()).isEqualTo("heuristic-fallback");
    }
}
