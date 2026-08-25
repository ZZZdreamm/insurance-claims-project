package com.kmultan.claims.infrastructure.security;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.domain.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDate;

import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Role and ownership rules, exercised through real tokens on the real filter chain. */
@AutoConfigureMockMvc
class SecurityIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokens tokens;

    private static final String VALID = """
            {"policyNumber":"POL-SEC","plateNumber":"SE 1","incidentDate":"%s",
             "description":"Security integration test claim, windscreen cracked","estimatedAmount":100}
            """.formatted(LocalDate.now());

    private String anna, marek, alice, bob, finance;

    private void tokensUp() {
        anna = TestTokens.bearer(tokens, "anna", Role.POLICYHOLDER);
        marek = TestTokens.bearer(tokens, "marek", Role.POLICYHOLDER);
        alice = TestTokens.bearer(tokens, "alice", Role.ADJUSTER);
        bob = TestTokens.bearer(tokens, "bob", Role.ADJUSTER);
        finance = TestTokens.bearer(tokens, "finance", Role.FINANCE);
    }

    private String submitAs(String bearer) throws Exception {
        String body = mvc.perform(post("/api/v1/claims").header("Authorization", bearer).header("X-Client-Id", "sec-" + bearer.hashCode())
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void loginIssuesTokenAndMeReflectsRoles() throws Exception {
        String token = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content("{\"username\":\"alice\",\"password\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles[0]").value("ADJUSTER"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAndWrongRolesAreRejected() throws Exception {
        tokensUp();
        mvc.perform(get("/api/v1/claims")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.title").value("Authentication required"));
        mvc.perform(get("/api/v1/claims").header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reviews").header("Authorization", anna)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/claims").header("Authorization", alice).contentType(APPLICATION_JSON).content(VALID)).andExpect(status().isForbidden());
    }

    @Test
    void policyholdersOnlySeeTheirOwnClaims() throws Exception {
        tokensUp();
        String annas = submitAs(anna);
        mvc.perform(get("/api/v1/claims/{id}", annas).header("Authorization", anna)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/claims/{id}", annas).header("Authorization", marek)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/claims/{id}", annas).header("Authorization", alice)).andExpect(status().isOk());   // staff
        mvc.perform(get("/api/v1/claims").header("Authorization", marek))
                .andExpect(jsonPath("$.content[?(@.id == '" + annas + "')]").doesNotExist());
        mvc.perform(post("/api/v1/claims/{id}/withdraw", annas).header("Authorization", marek)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/claims/{id}/withdraw", annas).header("Authorization", anna)).andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void reviewIsHeldByTheCallerAndOnlyTheHolderDecides() throws Exception {
        tokensUp();
        String id = submitAs(anna);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/api/v1/claims/{id}", id).header("Authorization", alice)).andExpect(jsonPath("$.status").value("PENDING_REVIEW")));

        mvc.perform(post("/api/v1/reviews/{id}/claim", id).header("Authorization", alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewAssignee").value("alice"));
        mvc.perform(post("/api/v1/reviews/{id}/approve", id).header("Authorization", bob).contentType(APPLICATION_JSON).content("{\"approvedAmount\":90}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Review is held by alice"));
        mvc.perform(post("/api/v1/reviews/{id}/approve", id).header("Authorization", alice).contentType(APPLICATION_JSON).content("{\"approvedAmount\":90.99}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/claims/{id}", id).header("Authorization", finance)).andExpect(jsonPath("$.status").value("PAYOUT_FAILED")));
        mvc.perform(post("/api/v1/claims/{id}/retry-payout", id).header("Authorization", alice)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/claims/{id}/retry-payout", id).header("Authorization", finance).contentType(APPLICATION_JSON).content("{\"approvedAmount\":91}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }
}
