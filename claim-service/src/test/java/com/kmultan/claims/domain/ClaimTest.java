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

    private static Claim submitted() {
        return Claim.submit("CLM-2026-000001", "POL-1", "wa 12345", LocalDate.now().minusDays(1),
                "Rear bumper dented in a parking lot", new BigDecimal("1200.00"));
    }

    private static Claim pendingReview() {
        Claim c = submitted();
        c.completeAssessment(Severity.MODERATE, new BigDecimal("1500.00"), "test", Instant.now().plusSeconds(3600));
        return c;
    }

    @Test
    void newClaimStartsSubmittedWithNormalisedPlate() {
        Claim c = submitted();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(c.getPlateNumber()).isEqualTo("WA12345");
        assertThat(c.getId()).isNotNull();
    }

    @Test
    void rejectsFutureIncidentDate() {
        assertThatThrownBy(() -> Claim.submit("n", "p", "x", LocalDate.now().plusDays(1), "d", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assessmentMovesToReviewAndRecordsTriage() {
        Claim c = pendingReview();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        assertThat(c.getSeverity()).isEqualTo(Severity.MODERATE);
        assertThat(c.getEstimatedAmount()).isEqualByComparingTo("1500.00");
        assertThat(c.getReviewDueAt()).isNotNull();
    }

    @Test
    void happyPathToPaid() {
        Claim c = pendingReview();
        c.claimReview("alice");
        c.approve(new BigDecimal("1400.00"));
        c.markPaid();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.PAID);
        assertThat(c.getApprovedAmount()).isEqualByComparingTo("1400.00");
    }

    @Test
    void cannotApproveBeforeReview() {
        Claim c = submitted();
        assertThatThrownBy(() -> c.approve(BigDecimal.TEN))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("SUBMITTED").hasMessageContaining("APPROVED");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
    }

    @Test
    void reviewCanOnlyBeClaimedByOneAdjuster() {
        Claim c = pendingReview();
        c.claimReview("alice");
        c.claimReview("alice");   // idempotent for the same person
        assertThatThrownBy(() -> c.claimReview("bob")).isInstanceOf(IllegalStateException.class);
        c.unclaimReview();
        c.claimReview("bob");
        assertThat(c.getReviewAssignee()).isEqualTo("bob");
    }

    @Test
    void escalationHappensOnceAndKeepsTheReviewOpen() {
        Claim c = pendingReview();
        Instant now = Instant.now();
        assertThat(c.escalateReview(now)).isTrue();
        assertThat(c.escalateReview(now.plusSeconds(60))).isFalse();
        assertThat(c.getEscalatedAt()).isEqualTo(now);
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
    }

    @Test
    void rejectRequiresReason() {
        Claim c = pendingReview();
        assertThatThrownBy(() -> c.reject(" ")).isInstanceOf(IllegalArgumentException.class);
        c.reject("Policy lapsed");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(c.getRejectionReason()).isEqualTo("Policy lapsed");
    }

    @Test
    void failedPayoutCanBeRetriedWithCorrectedAmount() {
        Claim c = pendingReview();
        c.approve(new BigDecimal("100.99"));
        c.markPayoutFailed("provider rejected");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.PAYOUT_FAILED);
        assertThat(c.getPayoutFailureReason()).isEqualTo("provider rejected");

        c.retryPayout(new BigDecimal("101.00"));
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(c.getApprovedAmount()).isEqualByComparingTo("101.00");
        assertThat(c.getPayoutFailureReason()).isNull();

        c.markPayoutFailed("again");
        c.retryPayout(null);   // keep the amount
        assertThat(c.getApprovedAmount()).isEqualByComparingTo("101.00");
        c.markPaid();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.PAID);
    }

    @Test
    void retryOnlyFromPayoutFailed() {
        Claim c = pendingReview();
        assertThatThrownBy(() -> c.retryPayout(null)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"REJECTED", "PAID", "WITHDRAWN"})
    void terminalStatesHaveNoTransitions(ClaimStatus s) {
        assertThat(s.isTerminal()).isTrue();
    }

    @Test
    void withdrawnClaimCannotBeReopened() {
        Claim c = submitted();
        c.withdraw();
        assertThatThrownBy(() -> c.completeAssessment(Severity.MINOR, null, "x", null)).isInstanceOf(InvalidStateTransitionException.class);
    }
}
