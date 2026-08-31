package com.kmultan.claims.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Policy;
import com.kmultan.platform.security.TestJwtTokenFactory;

/** A claim is only as good as the policy behind it: coverage checks at intake, cap and deductible at settlement. */
@AutoConfigureMockMvc
class PolicyAndSettlementIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClaimService claimService;

    @Autowired
    ObjectMapper objectMapper;

    private String submitBody(String policyNumber, LocalDate incidentDate) {
        return """
                {"policyNumber":"%s","plateNumber":"PS %05d","incidentDate":"%s",
                 "description":"Settlement rules test damage description","estimatedAmount":900.00}
                """
                .formatted(policyNumber, (int) (Math.random() * 89999) + 10000, incidentDate);
    }

    private org.springframework.test.web.servlet.ResultActions submitAs(
            String bearer, String policyNumber, LocalDate incidentDate) throws Exception {
        return mockMvc.perform(post("/api/v1/claims")
                .header("Authorization", bearer)
                .contentType(APPLICATION_JSON)
                .content(submitBody(policyNumber, incidentDate)));
    }

    @Test
    void unknownPolicyIsRejectedAtIntake() throws Exception {
        submitAs(
                        TOKENS.bearer("anna", "POLICYHOLDER"),
                        "POL-DOES-NOT-EXIST",
                        LocalDate.now().minusDays(1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Policy validation failed"));
    }

    @Test
    void incidentOutsideTheCoveragePeriodIsRejected() throws Exception {
        policyRepository.save(new Policy(
                "POL-EXPIRED",
                null,
                Policy.CoverageType.OC,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2021, 12, 31),
                new BigDecimal("100000"),
                BigDecimal.ZERO));
        submitAs(
                        TOKENS.bearer("anna", "POLICYHOLDER"),
                        "POL-EXPIRED",
                        LocalDate.now().minusDays(1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("does not cover")));
    }

    @Test
    void someoneElsesPolicyIsRejected() throws Exception {
        policyRepository.save(new Policy(
                "POL-MAREKS",
                TestJwtTokenFactory.subjectOf("marek"),
                Policy.CoverageType.AC,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2035, 12, 31),
                new BigDecimal("100000"),
                BigDecimal.ZERO));
        submitAs(
                        TOKENS.bearer("anna", "POLICYHOLDER"),
                        "POL-MAREKS",
                        LocalDate.now().minusDays(1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different policyholder")));
    }

    @Test
    void settlementCapsAtTheSumInsuredAndDeductsTheDeductible() throws Exception {
        policyRepository.save(new Policy(
                "POL-CAPPED",
                null,
                Policy.CoverageType.AC,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2035, 12, 31),
                new BigDecimal("5000"),
                new BigDecimal("500")));
        String body = submitAs(
                        TOKENS.bearer("anna", "POLICYHOLDER"),
                        "POL-CAPPED",
                        LocalDate.now().minusDays(1))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        await().atMost(Duration.ofSeconds(90))
                .until(() -> claimService.get(id).getStatus() == ClaimStatus.PENDING_REVIEW);

        // gross 6000 -> capped at 5000, minus 500 deductible = 4500 payable
        claimService.approve(id, new BigDecimal("6000"), null, "alice");
        assertThat(claimService.get(id).getGrossApprovedAmount()).isEqualByComparingTo("6000");
        assertThat(claimService.get(id).getDeductibleApplied()).isEqualByComparingTo("500");
        assertThat(claimService.get(id).getPayableAmount()).isEqualByComparingTo("4500");

        await().atMost(Duration.ofSeconds(90)).until(() -> claimService.get(id).getStatus() == ClaimStatus.PAID);
        assertThat(claimService.get(id).getPaidAmount()).isEqualByComparingTo("4500");

        mockMvc.perform(get("/api/v1/claims/{id}", id).header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(jsonPath("$.payableAmount").value(4500.0))
                .andExpect(jsonPath("$.deductibleApplied").value(500.0));
    }

    @Test
    void awardBelowTheDeductibleIsNotPayable() throws Exception {
        policyRepository.save(new Policy(
                "POL-HIGH-DEDUCTIBLE",
                null,
                Policy.CoverageType.AC,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2035, 12, 31),
                new BigDecimal("100000"),
                new BigDecimal("2000")));
        String body = submitAs(
                        TOKENS.bearer("anna", "POLICYHOLDER"),
                        "POL-HIGH-DEDUCTIBLE",
                        LocalDate.now().minusDays(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        await().atMost(Duration.ofSeconds(90))
                .until(() -> claimService.get(id).getStatus() == ClaimStatus.PENDING_REVIEW);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> claimService.approve(id, new BigDecimal("1500"), null, "alice"))
                .isInstanceOf(com.kmultan.claims.domain.PolicyValidationException.class)
                .hasMessageContaining("deductible");
    }

    @Test
    void policyholderSeesOwnPoliciesOnly() throws Exception {
        policyRepository.save(new Policy(
                "POL-ANNAS-OWN",
                TestJwtTokenFactory.subjectOf("anna"),
                Policy.CoverageType.AC,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2030, 12, 31),
                new BigDecimal("250000"),
                new BigDecimal("400")));
        mockMvc.perform(get("/api/v1/policies/mine").header("Authorization", TOKENS.bearer("anna", "POLICYHOLDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.policyNumber == 'POL-ANNAS-OWN')]").exists())
                .andExpect(jsonPath("$[?(@.policyNumber == 'POL-1')]").doesNotExist());
    }
}
