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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.platform.security.TestJwtTokenFactory;

/**
 * The paper trail around the lifecycle: every step writes to the customer
 * communication history, the decision letter renders as a real PDF, and a paid
 * claim can be recovered from the liable third party (subrogation).
 */
@AutoConfigureMockMvc
class SubrogationAndCommunicationsIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClaimService claimService;

    @Autowired
    ObjectMapper objectMapper;

    private String anna() {
        return TOKENS.bearer("anna", "POLICYHOLDER");
    }

    private String finance() {
        return TOKENS.bearer("finance", "FINANCE");
    }

    private UUID submitAndAwaitReview(String description) throws Exception {
        String body = mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", anna())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"policyNumber":"POL-1","plateNumber":"SB %05d","incidentDate":"%s",
                                 "description":"%s","estimatedAmount":800.00}
                                """
                                        .formatted(
                                                (int) (Math.random() * 89999) + 10000,
                                                LocalDate.now().minusDays(1),
                                                description)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        await().atMost(Duration.ofSeconds(90))
                .until(() -> claimService.get(id).getStatus() == ClaimStatus.PENDING_REVIEW);
        return id;
    }

    private UUID paidClaim() throws Exception {
        UUID id = submitAndAwaitReview("Subrogation flow: rear-ended by an identified third party");
        claimService.approve(id, new BigDecimal("800"), null, "alice");
        await().atMost(Duration.ofSeconds(90)).until(() -> claimService.get(id).getStatus() == ClaimStatus.PAID);
        return id;
    }

    @Test
    void everyLifecycleStepLeavesACommunicationForTheCustomer() throws Exception {
        UUID id = paidClaim();
        mockMvc.perform(get("/api/v1/claims/{id}/communications", id).header("Authorization", anna()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CLAIM_RECEIVED"))
                .andExpect(jsonPath("$[1].type").value("ASSESSMENT_COMPLETED"))
                .andExpect(jsonPath("$[2].type").value("DECISION_APPROVED"))
                .andExpect(jsonPath("$[3].type").value("CLAIM_PAID"))
                .andExpect(jsonPath("$[3].body").value(org.hamcrest.Matchers.containsString("800.00 PLN")));
    }

    @Test
    void decisionLetterRendersAsPdfWithTheSettlement() throws Exception {
        UUID id = paidClaim();
        byte[] pdf = mockMvc.perform(
                        get("/api/v1/claims/{id}/decision-document", id).header("Authorization", anna()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", "application/pdf"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(claimService.get(id).getClaimNumber());
            assertThat(text).contains("Amount payable");
            assertThat(text).contains("800.00 PLN");
        }
    }

    @Test
    void rejectionLetterCarriesTheReasonAndNoLetterExistsBeforeADecision() throws Exception {
        UUID id = submitAndAwaitReview("Rejection letter flow: pre-existing damage suspected");
        mockMvc.perform(get("/api/v1/claims/{id}/decision-document", id).header("Authorization", anna()))
                .andExpect(status().isConflict());

        claimService.reject(id, "The damage predates the policy period");
        byte[] pdf = mockMvc.perform(
                        get("/api/v1/claims/{id}/decision-document", id).header("Authorization", anna()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("The damage predates the policy period");
        }
    }

    @Test
    void paidClaimIsRecoveredFromTheLiablePartyInInstalments() throws Exception {
        UUID id = paidClaim();

        String created = mockMvc.perform(post("/api/v1/claims/{id}/subrogation", id)
                        .header("Authorization", TOKENS.bearer("alice", "ADJUSTER"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"liableParty\":\"Other Insurer S.A.\",\"expectedAmount\":800.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String subrogationId = objectMapper.readTree(created).get("id").asText();

        // a second case for the same claim is refused
        mockMvc.perform(post("/api/v1/claims/{id}/subrogation", id)
                        .header("Authorization", TOKENS.bearer("alice", "ADJUSTER"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"liableParty\":\"Other Insurer S.A.\",\"expectedAmount\":800.00}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/subrogations/{id}/recoveries", subrogationId)
                        .header("Authorization", finance())
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":300.00}"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.recoveredAmount").value(300.0));
        mockMvc.perform(post("/api/v1/subrogations/{id}/recoveries", subrogationId)
                        .header("Authorization", finance())
                        .contentType(APPLICATION_JSON)
                        .content("{\"amount\":500.00}"))
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.recoveredAmount").value(800.0));

        // a closed case takes no further money and no write-off
        mockMvc.perform(post("/api/v1/subrogations/{id}/write-off", subrogationId)
                        .header("Authorization", finance())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/subrogations/summary").header("Authorization", finance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRecovered").value(org.hamcrest.Matchers.greaterThanOrEqualTo(800.0)));
    }

    @Test
    void subrogationNeedsAPaidClaim() throws Exception {
        UUID id = submitAndAwaitReview("Subrogation guard: still under review");
        mockMvc.perform(post("/api/v1/claims/{id}/subrogation", id)
                        .header("Authorization", TOKENS.bearer("alice", "ADJUSTER"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"liableParty\":\"Other Insurer S.A.\",\"expectedAmount\":100.00}"))
                .andExpect(status().isConflict());
    }
}
