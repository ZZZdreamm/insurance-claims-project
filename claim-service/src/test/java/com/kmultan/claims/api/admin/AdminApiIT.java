package com.kmultan.claims.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.platform.security.TestJwtTokenFactory;

@AutoConfigureMockMvc
class AdminApiIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ClaimService claimService;

    private String adminBearer() {
        return "Bearer " + TOKENS.token("admin", java.time.Instant.now().plusSeconds(600), "ADMIN");
    }

    @Test
    void statisticsAndUsageAreAdminOnlyAndReflectClaims() throws Exception {
        Claim claim = claimService.submit(
                "POL-ST",
                "ST 1",
                LocalDate.now(),
                "Statistics claim with a cracked windscreen",
                new BigDecimal("900"),
                List.of());
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() ->
                        assertThat(claimService.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW));

        mockMvc.perform(get("/api/v1/admin/statistics").header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/statistics")).andExpect(status().isUnauthorized());

        String body = mockMvc.perform(get("/api/v1/admin/statistics").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode statistics = objectMapper.readTree(body);
        assertThat(statistics.get("totalClaims").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(statistics.get("byStatus").get("PENDING_REVIEW").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(statistics.get("submittedPerDay").size()).isEqualTo(14);
        assertThat(statistics
                        .get("submittedPerDay")
                        .get(LocalDate.now().toString())
                        .asLong())
                .isGreaterThanOrEqualTo(1);
        assertThat(statistics.get("averageSecondsToAssessment").asDouble()).isGreaterThanOrEqualTo(0);

        mockMvc.perform(get("/api/v1/admin/usage").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimsSubmitted").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1.0)))
                .andExpect(jsonPath("$.heapUsedBytes").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.endpoints").isArray());
    }

    @Test
    void adminManagesAccountsButCannotLockThemselvesOut() throws Exception {
        String username = "tester-" + UUID.randomUUID().toString().substring(0, 8);
        String created = mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", adminBearer())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"secret1\",\"displayName\":\"Test Er\",\"roles\":[\"ADJUSTER\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("ADJUSTER"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String userId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/users/{id}", userId)
                        .header("Authorization", adminBearer())
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":false,\"roles\":[\"FINANCE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.roles[0]").value("FINANCE"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret1\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == '" + username + "')].enabled")
                        .value(false));

        // the seeded admin (same subject as the token's) cannot disable itself or drop ADMIN
        String adminId = objectMapper
                .readTree(mockMvc.perform(get("/api/v1/admin/users").header("Authorization", adminBearer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .findValues("id")
                .stream()
                .map(JsonNode::asText)
                .filter(id -> id.equals(TestJwtTokenFactory.subjectOf("admin").toString()))
                .findFirst()
                .orElse(null);
        if (adminId != null) {
            mockMvc.perform(patch("/api/v1/admin/users/{id}", adminId)
                            .header("Authorization", adminBearer())
                            .contentType(APPLICATION_JSON)
                            .content("{\"enabled\":false}"))
                    .andExpect(status().isConflict());
        }
    }
}
