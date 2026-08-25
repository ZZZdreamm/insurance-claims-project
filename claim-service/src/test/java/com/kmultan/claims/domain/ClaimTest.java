package com.kmultan.claims.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimTest {

    private static Claim submittedClaim() {
        return Claim.submit("CLM-2026-000001", "POL-1", "wa 12345", LocalDate.now().minusDays(1),
                "Rear bumper dented in a parking lot", new BigDecimal("1200.00"));
    }

    private static Claim claimPendingReview() {
        Claim claim = submittedClaim();
        claim.completeAssessment(Severity.MODERATE, new BigDecimal("1500.00"), "test", Instant.now().plusSeconds(3600));
        return claim;
    }

    @Test
    void newClaimStartsSubmittedWithNormalisedPlate() {
        Claim claim = submittedClaim();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(claim.getPlateNumber()).isEqualTo("WA12345");
        assertThat(claim.getId()).isNotNull();
    }

    @Test
    void rejectsFutureIncidentDate() {
        assertThatThrownBy(() -> Claim.submit("n", "p", "x", LocalDate.now().plusDays(1), "d", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assessmentMovesToReviewAndRecordsTriage() {
        Claim claim = claimPendingReview();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        assertThat(claim.getSeverity()).isEqualTo(Severity.MODERATE);
        assertThat(claim.getEstimatedAmount()).isEqualByComparingTo("1500.00");
        assertThat(claim.getReviewDueAt()).isNotNull();
    }

    @Test
    void happyPathToPaid() {
        Claim claim = claimPendingReview();
        claim.claimReview("alice");
        claim.approve(new BigDecimal("1400.00"));
        claim.markPaid();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PAID);
        assertThat(claim.getApprovedAmount()).isEqualByComparingTo("1400.00");
    }

    @Test
    void cannotApproveBeforeReview() {
        Claim claim = submittedClaim();
        assertThatThrownBy(() -> claim.approve(BigDecimal.TEN))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("SUBMITTED").hasMessageContaining("APPROVED");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
    }

    @Test
    void reviewCanOnlyBeClaimedByOneAdjuster() {
        Claim claim = claimPendingReview();
        claim.claimReview("alice");
        claim.claimReview("alice");   // idempotent for the same person
        assertThatThrownBy(() -> claim.claimReview("bob")).isInstanceOf(IllegalStateException.class);
        claim.unclaimReview();
        claim.claimReview("bob");
        assertThat(claim.getReviewAssignee()).isEqualTo("bob");
    }

    @Test
    void escalationHappensOnceAndKeepsTheReviewOpen() {
        Claim claim = claimPendingReview();
        Instant now = Instant.now();
        assertThat(claim.escalateReview(now)).isTrue();
        assertThat(claim.escalateReview(now.plusSeconds(60))).isFalse();
        assertThat(claim.getEscalatedAt()).isEqualTo(now);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
    }

    @Test
    void rejectRequiresReason() {
        Claim claim = claimPendingReview();
        assertThatThrownBy(() -> claim.reject(" ")).isInstanceOf(IllegalArgumentException.class);
        claim.reject("Policy lapsed");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(claim.getRejectionReason()).isEqualTo("Policy lapsed");
    }

    @Test
    void failedPayoutCanBeRetriedWithCorrectedAmount() {
        Claim claim = claimPendingReview();
        claim.approve(new BigDecimal("100.99"));
        claim.markPayoutFailed("provider rejected");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PAYOUT_FAILED);
        assertThat(claim.getPayoutFailureReason()).isEqualTo("provider rejected");

        claim.retryPayout(new BigDecimal("101.00"));
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getApprovedAmount()).isEqualByComparingTo("101.00");
        assertThat(claim.getPayoutFailureReason()).isNull();

        claim.markPayoutFailed("again");
        claim.retryPayout(null);   // keep the amount
        assertThat(claim.getApprovedAmount()).isEqualByComparingTo("101.00");
        claim.markPaid();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PAID);
    }

    @Test
    void retryOnlyFromPayoutFailed() {
        Claim claim = claimPendingReview();
        assertThatThrownBy(() -> claim.retryPayout(null)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"REJECTED", "PAID", "WITHDRAWN"})
    void terminalStatesHaveNoTransitions(ClaimStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @Test
    void withdrawnClaimCannotBeReopened() {
        Claim claim = submittedClaim();
        claim.withdraw();
        assertThatThrownBy(() -> claim.completeAssessment(Severity.MINOR, null, "x", null)).isInstanceOf(InvalidStateTransitionException.class);
    }
}
