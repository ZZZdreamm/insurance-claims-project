package com.kmultan.claims.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.platform.security.TestJwtTokenFactory;

/** The queue must stay usable with thousands of open reviews: paged, filterable, with cheap counters. */
@AutoConfigureMockMvc
class ReviewQueueIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClaimService claimService;

    @Test
    void queueIsPagedAndFilterableByScopeAndSeverity() throws Exception {
        Claim mild = claimService.submit(
                "POL-Q", "Q 1", LocalDate.now(), "Queue test: scratched bumper", new BigDecimal("300"), List.of());
        Claim fire = claimService.submit(
                "POL-Q", "Q 2", LocalDate.now(), "Queue test: engine bay fire", new BigDecimal("300"), List.of());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(claimService.get(mild.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
            assertThat(claimService.get(fire.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        });
        String alice = TOKENS.bearer("alice", "ADJUSTER");
        String bob = TOKENS.bearer("bob", "ADJUSTER");

        mockMvc.perform(get("/api/v1/reviews").param("size", "1").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        mockMvc.perform(post("/api/v1/reviews/{id}/claim", mild.getId()).header("Authorization", alice))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/reviews")
                        .param("scope", "MINE")
                        .param("size", "100")
                        .header("Authorization", alice))
                .andExpect(jsonPath("$.content[*].id")
                        .value(org.hamcrest.Matchers.hasItem(mild.getId().toString())))
                .andExpect(jsonPath("$.content[*].id")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.hasItem(fire.getId().toString()))));
        mockMvc.perform(get("/api/v1/reviews")
                        .param("scope", "MINE")
                        .param("size", "100")
                        .header("Authorization", bob))
                .andExpect(jsonPath("$.content[*].id")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.hasItem(mild.getId().toString()))));
        mockMvc.perform(get("/api/v1/reviews")
                        .param("scope", "UNASSIGNED")
                        .param("severity", "SEVERE")
                        .param("size", "100")
                        .header("Authorization", bob))
                .andExpect(jsonPath("$.content[*].id")
                        .value(org.hamcrest.Matchers.hasItem(fire.getId().toString())))
                .andExpect(jsonPath("$.content[*].severity")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("SEVERE"))));

        mockMvc.perform(get("/api/v1/reviews/summary").header("Authorization", alice))
                .andExpect(jsonPath("$.mine").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.unassigned").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.severe").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/v1/reviews/summary").header("Authorization", TOKENS.bearer("anna", "POLICYHOLDER")))
                .andExpect(status().isForbidden());
    }
}
