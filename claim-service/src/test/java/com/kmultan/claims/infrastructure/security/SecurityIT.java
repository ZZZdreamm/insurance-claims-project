package com.kmultan.claims.infrastructure.security;

import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.platform.security.TestJwtTokenFactory;

/** Role and ownership rules, exercised through real tokens on the real filter chain. */
@AutoConfigureMockMvc
class SecurityIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private static final String VALID =
            """
            {"policyNumber":"POL-SEC","plateNumber":"SE 1","incidentDate":"%s",
             "description":"Security integration test claim, windscreen cracked","estimatedAmount":100}
            """
                    .formatted(LocalDate.now());

    private String anna;
    private String marek;
    private String alice;
    private String bob;
    private String finance;

    private void tokensUp() {
        anna = TOKENS.bearer("anna", "POLICYHOLDER");
        marek = TOKENS.bearer("marek", "POLICYHOLDER");
        alice = TOKENS.bearer("alice", "ADJUSTER");
        bob = TOKENS.bearer("bob", "ADJUSTER");
        finance = TOKENS.bearer("finance", "FINANCE");
    }

    private String submitAs(String bearer) throws Exception {
        String body = mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", bearer)
                        .header("X-Client-Id", "sec-" + bearer.hashCode())
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void loginIssuesTokenAndMeReflectsRoles() throws Exception {
        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles[0]").value("ADJUSTER"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(loginBody).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAndWrongRolesAreRejected() throws Exception {
        tokensUp();
        mockMvc.perform(get("/api/v1/claims"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication required"));
        mockMvc.perform(get("/api/v1/claims").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reviews").header("Authorization", anna)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", alice)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isForbidden());
    }

    @Test
    void policyholdersOnlySeeTheirOwnClaims() throws Exception {
        tokensUp();
        String annas = submitAs(anna);
        mockMvc.perform(get("/api/v1/claims/{id}", annas).header("Authorization", anna))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/claims/{id}", annas).header("Authorization", marek))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/claims/{id}", annas).header("Authorization", alice))
                .andExpect(status().isOk()); // staff
        mockMvc.perform(get("/api/v1/claims").header("Authorization", marek))
                .andExpect(jsonPath("$.content[?(@.id == '" + annas + "')]").doesNotExist());
        mockMvc.perform(post("/api/v1/claims/{id}/withdraw", annas).header("Authorization", marek))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/claims/{id}/withdraw", annas).header("Authorization", anna))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void reviewIsHeldByTheCallerAndOnlyTheHolderDecides() throws Exception {
        tokensUp();
        String id = submitAs(anna);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mockMvc.perform(
                        get("/api/v1/claims/{id}", id).header("Authorization", alice))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW")));

        mockMvc.perform(post("/api/v1/reviews/{id}/claim", id).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewAssignee").value("alice"));
        mockMvc.perform(post("/api/v1/reviews/{id}/approve", id)
                        .header("Authorization", bob)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvedAmount\":90}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Review is held by alice"));
        mockMvc.perform(post("/api/v1/reviews/{id}/approve", id)
                        .header("Authorization", alice)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvedAmount\":90.99}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockMvc.perform(
                        get("/api/v1/claims/{id}", id).header("Authorization", finance))
                .andExpect(jsonPath("$.status").value("PAYOUT_FAILED")));
        mockMvc.perform(post("/api/v1/claims/{id}/retry-payout", id).header("Authorization", alice))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/claims/{id}/retry-payout", id)
                        .header("Authorization", finance)
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvedAmount\":91}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void expiredAndTamperedTokensAreRejected() throws Exception {
        String expired = TOKENS.expiredBearer("alice", "ADJUSTER");
        mockMvc.perform(get("/api/v1/reviews").header("Authorization", expired)).andExpect(status().isUnauthorized());

        String forged = new TestJwtTokenFactory("another-secret-that-is-also-32-bytes-long!!").bearer("admin", "ADMIN");
        mockMvc.perform(get("/api/v1/reviews").header("Authorization", forged)).andExpect(status().isUnauthorized());

        // role escalation by editing the payload breaks the signature
        String valid = TOKENS.bearer("anna", "POLICYHOLDER").substring("Bearer ".length());
        String[] parts = valid.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1])).replace("POLICYHOLDER", "ADMIN");
        String tampered = parts[0] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + parts[2];
        mockMvc.perform(get("/api/v1/reviews").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }
}
