package com.kmultan.claims.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.platform.security.TestJwtTokenFactory;

/** The q parameter every paged table exposes: narrow by claim number, plate, policy or description. */
@AutoConfigureMockMvc
class TableSearchIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClaimService claimService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void claimListAndReviewQueueNarrowByFreeText() throws Exception {
        String marker = "Searchable" + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", TOKENS.bearer("anna", "POLICYHOLDER"))
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"policyNumber":"POL-1","plateNumber":"TS 77001","incidentDate":"%s",
                                 "description":"Unique marker %s inside the description","estimatedAmount":300.00}
                                """
                                        .formatted(LocalDate.now().minusDays(1), marker)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        Awaitility.await()
                .atMost(Duration.ofSeconds(90))
                .until(() -> claimService.get(id).getStatus() == ClaimStatus.PENDING_REVIEW);

        // the claims table finds it by the description marker; a nonsense query finds nothing
        mockMvc.perform(get("/api/v1/claims")
                        .param("q", marker)
                        .header("Authorization", TOKENS.bearer("anna", "POLICYHOLDER")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()));
        mockMvc.perform(get("/api/v1/claims")
                        .param("q", "no-such-claim-anywhere")
                        .header("Authorization", TOKENS.bearer("anna", "POLICYHOLDER")))
                .andExpect(jsonPath("$.totalElements").value(0));

        // the review queue narrows by plate
        mockMvc.perform(get("/api/v1/reviews")
                        .param("q", "TS77001")
                        .param("size", "50")
                        .header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());
    }
}
