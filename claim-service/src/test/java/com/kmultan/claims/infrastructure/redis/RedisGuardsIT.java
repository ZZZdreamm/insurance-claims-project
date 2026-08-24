package com.kmultan.claims.infrastructure.redis;

import com.kmultan.claims.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Redis: client retries with the same Idempotency-Key never create a second claim; runaway clients get 429. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "claims.ratelimit.submit-per-minute=5")
class RedisGuardsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private static final String VALID = """
            {"policyNumber":"POL-RD","plateNumber":"RD 1","incidentDate":"%s",
             "description":"Redis guards integration test claim","estimatedAmount":100}
            """.formatted(LocalDate.now());

    @Test
    void sameIdempotencyKeyReplaysTheFirstClaim() throws Exception {
        String key = "mobile-" + UUID.randomUUID();
        String first = mvc.perform(post("/api/v1/claims").header("X-Client-Id", "idem-test").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = first.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/v1/claims").header("X-Client-Id", "idem-test").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/v1/claims/" + id))
                .andExpect(jsonPath("$.id").value(id));

        // a different key is a different submission
        String second = mvc.perform(post("/api/v1/claims").header("X-Client-Id", "idem-test").header("Idempotency-Key", "mobile-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(second.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")).isNotEqualTo(id);
    }

    @Test
    void failedSubmissionDoesNotBurnTheKey() throws Exception {
        String key = "mobile-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/claims").header("X-Client-Id", "idem-test2").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content("{\"policyNumber\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/claims").header("X-Client-Id", "idem-test2").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/claims").header("X-Client-Id", "idem-test2").header("Idempotency-Key", "bad key!").contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submissionsAreRateLimitedPerClient() throws Exception {
        String client = "burst-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/claims").header("X-Client-Id", client).contentType(APPLICATION_JSON).content(VALID))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(4 - i)));
        }
        mvc.perform(post("/api/v1/claims").header("X-Client-Id", client).contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many submissions"));
        // other clients are unaffected
        mvc.perform(post("/api/v1/claims").header("X-Client-Id", "other-" + UUID.randomUUID()).contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());
    }
}
