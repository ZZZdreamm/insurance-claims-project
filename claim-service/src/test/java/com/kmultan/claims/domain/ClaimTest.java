package com.kmultan.claims.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimTest {

    private static Claim submitted() {
        return Claim.submit("CLM-2026-000001", "POL-1", "wa 12345", LocalDate.now().minusDays(1),
                "Rear bumper dented in a parking lot", new BigDecimal("1200.00"));
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
    void happyPathToPaid() {
        Claim c = submitted();
        c.startAssessment();
        c.completeAssessment(new BigDecimal("1500.00"));
        c.approve(new BigDecimal("1400.00"));
        c.markPaid();
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.PAID);
        assertThat(c.getEstimatedAmount()).isEqualByComparingTo("1500.00");
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
    void rejectRequiresReason() {
        Claim c = submitted();
        c.startAssessment();
        c.completeAssessment(null);
        assertThatThrownBy(() -> c.reject(" ")).isInstanceOf(IllegalArgumentException.class);
        c.reject("Policy lapsed");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(c.getRejectionReason()).isEqualTo("Policy lapsed");
    }

    @Test
    void payoutFailureIsTerminalAndKeepsReason() {
        Claim c = submitted();
        c.startAssessment();
        c.completeAssessment(null);
        c.approve(BigDecimal.TEN);
        c.markPayoutFailed("provider down");
        assertThat(c.getStatus()).isEqualTo(ClaimStatus.PAYOUT_FAILED);
        assertThat(c.getPayoutFailureReason()).isEqualTo("provider down");
        assertThatThrownBy(c::markPaid).isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"REJECTED", "PAID", "PAYOUT_FAILED", "WITHDRAWN"})
    void terminalStatesHaveNoTransitions(ClaimStatus s) {
        assertThat(s.isTerminal()).isTrue();
    }

    @Test
    void withdrawnClaimCannotBeReopened() {
        Claim c = submitted();
        c.withdraw();
        assertThatThrownBy(c::startAssessment).isInstanceOf(InvalidStateTransitionException.class);
    }
}
