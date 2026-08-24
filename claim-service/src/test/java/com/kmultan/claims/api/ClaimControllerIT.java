package com.kmultan.claims.api;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Postgres via Testcontainers: Flyway migrations, the sequence-backed
 * claim number generator and optimistic locking are all exercised for real.
 */
@AutoConfigureMockMvc
class ClaimControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ClaimRepository repository;
    @Autowired TransactionTemplate tx;

    private static final String VALID = """
            {"policyNumber":"POL-123","plateNumber":"WA 12345","incidentDate":"%s",
             "description":"Rear-ended at a red light, bumper and tail light damaged","estimatedAmount":2500.00}
            """.formatted(LocalDate.now().minusDays(2));

    @Test
    void submitReturns201WithGeneratedClaimNumber() throws Exception {
        mvc.perform(post("/api/v1/claims").contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.claimNumber").value(org.hamcrest.Matchers.matchesPattern("CLM-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.plateNumber").value("WA12345"));
    }

    @Test
    void validationErrorsAreProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/claims").contentType(APPLICATION_JSON)
                        .content("{\"policyNumber\":\"\",\"plateNumber\":\"!!\",\"incidentDate\":\"2999-01-01\",\"description\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void unknownClaimIs404() throws Exception {
        mvc.perform(get("/api/v1/claims/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Claim not found"));
    }

    @Test
    void illegalTransitionIs409() throws Exception {
        Claim c = tx.execute(s -> repository.save(Claim.submit("CLM-T-1", "P", "X1", LocalDate.now(), "desc desc desc", null)));
        mvc.perform(post("/api/v1/claims/{id}/approve", c.getId()).contentType(APPLICATION_JSON)
                        .content("{\"approvedAmount\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid state transition"));
    }

    @Test
    void fullLifecycleThroughApi() throws Exception {
        String body = mvc.perform(post("/api/v1/claims").contentType(APPLICATION_JSON).content(VALID))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // the process performs the assessment; wait for the review task, then decide through the task API
        String[] taskId = new String[1];
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            String tasks = mvc.perform(get("/api/v1/tasks")).andReturn().getResponse().getContentAsString();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"taskId\":\"([^\"]+)\",\"claimId\":\"" + id + "\"").matcher(tasks);
            assertThat(m.find()).isTrue();
            taskId[0] = m.group(1);
        });
        mvc.perform(get("/api/v1/claims/{id}", id)).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        mvc.perform(post("/api/v1/tasks/{t}/claim", taskId[0]).contentType(APPLICATION_JSON).content("{\"assignee\":\"alice\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/tasks/{t}/complete", taskId[0]).contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"approvedAmount\":2000}"))
                .andExpect(status().isNoContent());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/claims/{id}", id)).andExpect(jsonPath("$.status").value("PAID")));
        mvc.perform(post("/api/v1/tasks/{t}/complete", taskId[0]).contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"late\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void optimisticLockingRejectsStaleWrite() {
        Claim saved = tx.execute(s -> repository.save(Claim.submit("CLM-T-2", "P", "X2", LocalDate.now(), "desc desc desc", null)));
        UUID id = saved.getId();

        // Two detached copies of the same row (version 0)
        Claim a = repository.findById(id).orElseThrow();
        Claim b = repository.findById(id).orElseThrow();

        a.startAssessment();
        tx.executeWithoutResult(s -> repository.save(a));   // version -> 1

        b.withdraw();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.saveAndFlush(b)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("UNDER_ASSESSMENT");
    }
}
