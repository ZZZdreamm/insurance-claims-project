package com.kmultan.claims.api;

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

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.platform.security.TestJwtTokenFactory;

/** Real Postgres + Kafka: HTTP contract, multipart photos, and the lifecycle through the review API. */
@AutoConfigureMockMvc
class ClaimControllerIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    private String user() {
        return TOKENS.bearer("anna", "POLICYHOLDER");
    }

    private String adjuster() {
        return TOKENS.bearer("alice", "ADJUSTER");
    }

    private String finance() {
        return TOKENS.bearer("finance", "FINANCE");
    }

    private static final String VALID =
            """
            {"policyNumber":"POL-123","plateNumber":"WA 12345","incidentDate":"%s",
             "description":"Rear-ended at a red light, bumper and tail light damaged","estimatedAmount":2500.00}
            """
                    .formatted(LocalDate.now().minusDays(2));

    @Autowired
    ObjectMapper objectMapper;

    private String idOf(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody).get("id").asText();
    }

    @Test
    void submitReturns201WithGeneratedClaimNumber() throws Exception {
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
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
        String body = mockMvc.perform(multipart("/api/v1/claims")
                        .file(new MockMultipartFile("claim", "", "application/json", VALID.getBytes()))
                        .file(new MockMultipartFile("photos", "front.png", "image/png", png))
                        .file(new MockMultipartFile("photos", "side.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .header("Authorization", user()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoIds.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = idOf(body);
        String photoId = objectMapper.readTree(body).get("photoIds").get(0).asText();

        mockMvc.perform(get("/api/v1/claims/{id}/photos/{p}", id, photoId).header("Authorization", user()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(png));

        mockMvc.perform(multipart("/api/v1/claims")
                        .file(new MockMultipartFile("claim", "", "application/json", VALID.getBytes()))
                        .file(new MockMultipartFile("photos", "doc.pdf", "application/pdf", new byte[] {1}))
                        .header("Authorization", user()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void validationErrorsAreProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .contentType(APPLICATION_JSON)
                        .content("{\"policyNumber\":\"\",\"plateNumber\":\"!!\","
                                + "\"incidentDate\":\"2999-01-01\",\"description\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void unknownClaimIs404() throws Exception {
        mockMvc.perform(get("/api/v1/claims/{id}", UUID.randomUUID()).header("Authorization", adjuster()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Claim not found"));
    }

    @Test
    void illegalTransitionIs409() throws Exception {
        Claim claim = transactionTemplate.execute(status ->
                claimRepository.save(Claim.submit("CLM-T-1", "P", "X1", LocalDate.now(), "desc desc desc", null)));
        mockMvc.perform(post("/api/v1/reviews/{id}/approve", claim.getId())
                        .header("Authorization", TOKENS.bearer("admin", "ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvedAmount\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid state transition"));
        mockMvc.perform(post("/api/v1/claims/{id}/retry-payout", claim.getId()).header("Authorization", finance()))
                .andExpect(status().isConflict());
    }

    @Test
    void fullLifecycleThroughApi() throws Exception {
        String id = idOf(mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andReturn()
                .getResponse()
                .getContentAsString());

        // assessment-service (fake) reacts to CLAIM_SUBMITTED; the claim shows up in the review queue.
        // The queue is oldest-first and paged: ask for a large page, or claims accumulated by the rest
        // of the suite in the shared database push this one off page 0 and the condition can never hold.
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> mockMvc.perform(
                        get("/api/v1/reviews").param("size", "500").header("Authorization", adjuster()))
                .andExpect(
                        jsonPath("$.content[?(@.id == '" + id + "')].severity").value("MODERATE")));
        mockMvc.perform(get("/api/v1/claims")
                        .header("Authorization", adjuster())
                        .param("status", "PENDING_REVIEW")
                        .param("size", "500"))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());

        mockMvc.perform(post("/api/v1/reviews/{id}/claim", id).header("Authorization", adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewAssignee").value("alice"));
        mockMvc.perform(post("/api/v1/reviews/{id}/claim", id)
                        .header("Authorization", TOKENS.bearer("bob", "ADJUSTER")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/reviews/{id}/approve", id)
                        .header("Authorization", adjuster())
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvedAmount\":2000.99}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // payout-service (fake) fails on .99 -> PAYOUT_FAILED -> retry with corrected amount -> PAID
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> mockMvc.perform(
                        get("/api/v1/claims/{id}", id).header("Authorization", user()))
                .andExpect(jsonPath("$.status").value("PAYOUT_FAILED")));
        mockMvc.perform(post("/api/v1/claims/{id}/retry-payout", id)
                        .header("Authorization", finance())
                        .contentType(APPLICATION_JSON)
                        .content("{\"approvedAmount\":2001}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> mockMvc.perform(
                        get("/api/v1/claims/{id}", id).header("Authorization", user()))
                .andExpect(jsonPath("$.status").value("PAID")));
    }

    @Test
    void optimisticLockingRejectsStaleWrite() {
        Claim saved = transactionTemplate.execute(status ->
                claimRepository.save(Claim.submit("CLM-T-2", "P", "X2", LocalDate.now(), "desc desc desc", null)));
        UUID id = saved.getId();

        Claim firstCopy = claimRepository.findById(id).orElseThrow();
        Claim secondCopy = claimRepository.findById(id).orElseThrow();

        firstCopy.withdraw();
        transactionTemplate.executeWithoutResult(status -> claimRepository.save(firstCopy)); // version -> 1

        secondCopy.withdraw();
        assertThatThrownBy(() ->
                        transactionTemplate.executeWithoutResult(status -> claimRepository.saveAndFlush(secondCopy)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(claimRepository.findById(id).orElseThrow().getVersion()).isEqualTo(1);
    }
}
