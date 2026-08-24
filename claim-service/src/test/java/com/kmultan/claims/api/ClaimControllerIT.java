package com.kmultan.claims.api;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres + Kafka: HTTP contract, multipart photos, and the lifecycle through the review API. */
@AutoConfigureMockMvc
class ClaimControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ClaimRepository repository;
    @Autowired TransactionTemplate tx;

    private static final String VALID = """
            {"policyNumber":"POL-123","plateNumber":"WA 12345","incidentDate":"%s",
             "description":"Rear-ended at a red light, bumper and tail light damaged","estimatedAmount":2500.00}
            """.formatted(LocalDate.now().minusDays(2));

    private static String idOf(String body) {
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void submitReturns201WithGeneratedClaimNumber() throws Exception {
        mvc.perform(post("/api/v1/claims").contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.claimNumber").value(org.hamcrest.Matchers.matchesPattern("CLM-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.plateNumber").value("WA12345"))
                .andExpect(jsonPath("$.photoIds").isEmpty());
    }

    @Test
    void multipartSubmitStoresPhotosAndServesThemBack() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        String body = mvc.perform(multipart("/api/v1/claims")
                        .file(new MockMultipartFile("claim", "", "application/json", VALID.getBytes()))
                        .file(new MockMultipartFile("photos", "front.png", "image/png", png))
                        .file(new MockMultipartFile("photos", "side.jpg", "image/jpeg", new byte[]{1, 2, 3})))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoIds.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(body);
        String photoId = body.replaceAll(".*\"photoIds\":\\[\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/claims/{id}/photos/{p}", id, photoId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(png));

        mvc.perform(multipart("/api/v1/claims")
                        .file(new MockMultipartFile("claim", "", "application/json", VALID.getBytes()))
                        .file(new MockMultipartFile("photos", "doc.pdf", "application/pdf", new byte[]{1})))
                .andExpect(status().isUnprocessableEntity());
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
        mvc.perform(post("/api/v1/reviews/{id}/approve", c.getId()).contentType(APPLICATION_JSON).content("{\"approvedAmount\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid state transition"));
        mvc.perform(post("/api/v1/claims/{id}/retry-payout", c.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void fullLifecycleThroughApi() throws Exception {
        String id = idOf(mvc.perform(post("/api/v1/claims").contentType(APPLICATION_JSON).content(VALID))
                .andReturn().getResponse().getContentAsString());

        // assessment-service (fake) reacts to CLAIM_SUBMITTED; the claim shows up in the review queue
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/api/v1/reviews")).andExpect(jsonPath("$[?(@.id == '" + id + "')].severity").value("MODERATE")));
        mvc.perform(get("/api/v1/claims").param("status", "PENDING_REVIEW"))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());

        mvc.perform(post("/api/v1/reviews/{id}/claim", id).contentType(APPLICATION_JSON).content("{\"assignee\":\"alice\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewAssignee").value("alice"));
        mvc.perform(post("/api/v1/reviews/{id}/claim", id).contentType(APPLICATION_JSON).content("{\"assignee\":\"bob\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/reviews/{id}/approve", id).contentType(APPLICATION_JSON).content("{\"approvedAmount\":2000.99}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // payout-service (fake) fails on .99 -> PAYOUT_FAILED -> retry with corrected amount -> PAID
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/claims/{id}", id)).andExpect(jsonPath("$.status").value("PAYOUT_FAILED")));
        mvc.perform(post("/api/v1/claims/{id}/retry-payout", id).contentType(APPLICATION_JSON).content("{\"approvedAmount\":2001}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/claims/{id}", id)).andExpect(jsonPath("$.status").value("PAID")));
    }

    @Test
    void optimisticLockingRejectsStaleWrite() {
        Claim saved = tx.execute(s -> repository.save(Claim.submit("CLM-T-2", "P", "X2", LocalDate.now(), "desc desc desc", null)));
        UUID id = saved.getId();

        Claim a = repository.findById(id).orElseThrow();
        Claim b = repository.findById(id).orElseThrow();

        a.withdraw();
        tx.executeWithoutResult(s -> repository.save(a));   // version -> 1

        b.withdraw();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.saveAndFlush(b)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(1);
    }
}
