package com.kmultan.claims.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.application.FraudScreeningService;
import com.kmultan.claims.domain.ClaimPayment;
import com.kmultan.claims.domain.ClaimReserve;
import com.kmultan.claims.domain.ClaimReserveRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.platform.security.TestJwtTokenFactory;

/**
 * The governance features on top of the base lifecycle: four-eyes above the
 * approval limit, the advance/remainder payout split, fraud flags routing a
 * claim to the special-investigation view, and the reserve closing with the claim.
 */
@AutoConfigureMockMvc
class FourEyesAndPartialPayoutIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClaimService claimService;

    @Autowired
    ClaimReserveRepository reserveRepository;

    @Autowired
    ObjectMapper objectMapper;

    private UUID submitAndAwaitReview(String plate, String description) throws Exception {
        String body = mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", TOKENS.bearer("anna", "POLICYHOLDER"))
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"policyNumber":"POL-1","plateNumber":"%s","incidentDate":"%s",
                                 "description":"%s","estimatedAmount":1200.00}
                                """
                                        .formatted(plate, LocalDate.now().minusDays(1), description)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        await().atMost(Duration.ofSeconds(90))
                .until(() -> claimService.get(id).getStatus() == ClaimStatus.PENDING_REVIEW);
        return id;
    }

    @Test
    void aboveTheLimitTwoDifferentApproversAreRequired() throws Exception {
        UUID id = submitAndAwaitReview("FE 11111", "Four eyes flow: severe collision damage");

        claimService.approve(id, new BigDecimal("20000"), null, "alice");
        assertThat(claimService.get(id).getStatus()).isEqualTo(ClaimStatus.PENDING_SECOND_APPROVAL);
        assertThat(claimService.get(id).getFirstApprover()).isEqualTo("alice");

        // the parked claim is visible in the second-approvals queue
        mockMvc.perform(get("/api/v1/reviews/second-approvals")
                        .param("size", "500")
                        .header("Authorization", TOKENS.bearer("bob", "ADJUSTER")))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());

        // the first approver cannot confirm their own decision
        mockMvc.perform(post("/api/v1/reviews/{id}/second-approval", id)
                        .header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Four-eyes")));

        mockMvc.perform(post("/api/v1/reviews/{id}/second-approval", id)
                        .header("Authorization", TOKENS.bearer("bob", "ADJUSTER")))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(90)).until(() -> claimService.get(id).getStatus() == ClaimStatus.PAID);
    }

    @Test
    void advancePaysAShareAndFinanceReleasesTheRemainder() throws Exception {
        UUID id = submitAndAwaitReview("AD 22222", "Advance payout flow: repair in progress");

        claimService.approve(id, new BigDecimal("4000"), 25, "alice");
        await().atMost(Duration.ofSeconds(90))
                .until(() -> claimService.get(id).getStatus() == ClaimStatus.PARTIALLY_PAID);
        assertThat(claimService.get(id).getPaidAmount()).isEqualByComparingTo("1000");

        assertThat(claimService.paymentsOf(id))
                .extracting(ClaimPayment::getPaymentType)
                .containsExactly(ClaimPayment.PaymentType.ADVANCE);

        mockMvc.perform(post("/api/v1/claims/{id}/pay-remainder", id)
                        .header("Authorization", TOKENS.bearer("finance", "FINANCE")))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(90)).until(() -> claimService.get(id).getStatus() == ClaimStatus.PAID);
        assertThat(claimService.get(id).getPaidAmount()).isEqualByComparingTo("4000");
        assertThat(claimService.paymentsOf(id))
                .extracting(ClaimPayment::getPaymentType)
                .containsExactly(ClaimPayment.PaymentType.ADVANCE, ClaimPayment.PaymentType.FINAL);

        ClaimReserve reserve = reserveRepository.findByClaimId(id).orElseThrow();
        assertThat(reserve.getStatus()).isEqualTo(ClaimReserve.Status.SETTLED);
        assertThat(reserve.getCurrentAmount()).isZero();
    }

    @Test
    void duplicatePlateWithinTheWindowIsFlaggedForInvestigation() throws Exception {
        String plate = "FR 33333";
        submitAndAwaitReview(plate, "Fraud screen: first claim for this vehicle");
        UUID second = submitAndAwaitReview(plate, "Fraud screen: second claim same vehicle same week");

        assertThat(claimService.get(second).getFraudFlags()).contains(FraudScreeningService.DUPLICATE_CLAIM);

        mockMvc.perform(get("/api/v1/reviews")
                        .param("fraudOnly", "true")
                        .param("size", "500")
                        .header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(jsonPath("$.content[?(@.id == '" + second + "')].fraudFlags[0]")
                        .value(org.hamcrest.Matchers.hasItem(FraudScreeningService.DUPLICATE_CLAIM)));

        mockMvc.perform(get("/api/v1/reviews/summary").header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(jsonPath("$.fraudSuspected").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // the investigation context lists the first claim as the duplicate candidate to compare against
        mockMvc.perform(get("/api/v1/claims/{id}/fraud-context", second)
                        .header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateCandidates[0].plateNumber")
                        .value(claimService.get(second).getPlateNumber()))
                .andExpect(jsonPath("$.ownerClaims").isArray());
    }

    @Test
    void reserveIsReleasedWhenTheClaimIsRejected() throws Exception {
        UUID id = submitAndAwaitReview("RJ 44444", "Reserve release flow: claim to be rejected");
        assertThat(reserveRepository.findByClaimId(id).orElseThrow().getStatus())
                .isEqualTo(ClaimReserve.Status.OPEN);

        claimService.reject(id, "Damage predates the policy");
        ClaimReserve reserve = reserveRepository.findByClaimId(id).orElseThrow();
        assertThat(reserve.getStatus()).isEqualTo(ClaimReserve.Status.RELEASED);
        assertThat(reserve.getCurrentAmount()).isZero();

        assertThatThrownBy(() -> claimService.payRemainder(id)).isInstanceOf(Exception.class);
    }
}
