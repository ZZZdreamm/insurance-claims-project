package com.kmultan.claims.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.platform.security.TestJwtTokenFactory;

/** Real Redis: client retries with the same Idempotency-Key never create a second claim; runaway clients get 429. */
@AutoConfigureMockMvc
@Import(MutableClockConfiguration.class)
@TestPropertySource(properties = "claims.ratelimit.submit-per-minute=5")
class RedisGuardsIT extends AbstractIntegrationTest {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MutableClockConfiguration.MutableClock mutableClock;

    private String user() {
        return TOKENS.bearer("anna", "POLICYHOLDER");
    }

    private static final String VALID =
            """
            {"policyNumber":"POL-RD","plateNumber":"RD 1","incidentDate":"%s",
             "description":"Redis guards integration test claim","estimatedAmount":100}
            """
                    .formatted(LocalDate.now());

    @Test
    void sameIdempotencyKeyReplaysTheFirstClaim() throws Exception {
        String key = "mobile-" + UUID.randomUUID();
        String first = mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", "idem-test")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(first).get("id").asText();

        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", "idem-test")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/v1/claims/" + id))
                .andExpect(jsonPath("$.id").value(id));

        // a different key is a different submission
        String second = mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", "idem-test")
                        .header("Idempotency-Key", "mobile-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(second).get("id").asText()).isNotEqualTo(id);
    }

    @Test
    void failedSubmissionDoesNotBurnTheKey() throws Exception {
        String key = "mobile-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", "idem-test2")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"policyNumber\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", "idem-test2")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", "idem-test2")
                        .header("Idempotency-Key", "bad key!")
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submissionsAreRateLimitedPerClient() throws Exception {
        String client = "burst-" + UUID.randomUUID();
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(post("/api/v1/claims")
                            .header("Authorization", user())
                            .header("X-Client-Id", client)
                            .contentType(APPLICATION_JSON)
                            .content(VALID))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(4 - index)));
        }
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", client)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many submissions"));
        // other clients are unaffected
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", "other-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated());
    }

    @Test
    void limitResetsWhenTheWindowRollsOver() throws Exception {
        String client = "window-" + UUID.randomUUID();
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(post("/api/v1/claims")
                            .header("Authorization", user())
                            .header("X-Client-Id", client)
                            .contentType(APPLICATION_JSON)
                            .content(VALID))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", client)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isTooManyRequests());

        mutableClock.advanceSeconds(60); // next fixed window
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", user())
                        .header("X-Client-Id", client)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Remaining", "4"));
    }

    /** INCR is atomic: a burst of concurrent requests can never let more than the limit through. */
    @Test
    void concurrentBurstNeverExceedsTheLimit() throws Exception {
        String client = "burst-parallel-" + UUID.randomUUID();
        String bearer = user();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                results.add(pool.submit((Callable<Integer>) () -> mockMvc.perform(post("/api/v1/claims")
                                .header("Authorization", bearer)
                                .header("X-Client-Id", client)
                                .contentType(APPLICATION_JSON)
                                .content(VALID))
                        .andReturn()
                        .getResponse()
                        .getStatus()));
            }
            long created = 0;
            long limited = 0;
            for (Future<Integer> result : results) {
                int statusCode = result.get();
                if (statusCode == 201) created++;
                else if (statusCode == 429) limited++;
                else throw new AssertionError("unexpected " + statusCode);
            }
            assertThat(created).isEqualTo(5);
            assertThat(limited).isEqualTo(35);
        } finally {
            pool.shutdownNow();
        }
    }

    /** SET NX is atomic: concurrent retries with one Idempotency-Key produce exactly one claim. */
    @Test
    void concurrentRequestsWithSameIdempotencyKeyCreateOneClaim() throws Exception {
        String key = "parallel-" + UUID.randomUUID();
        String bearer = user();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                String client = "idem-par-" + index; // separate rate-limit buckets: only idempotency is under test
                results.add(pool.submit((Callable<Integer>) () -> mockMvc.perform(post("/api/v1/claims")
                                .header("Authorization", bearer)
                                .header("X-Client-Id", client)
                                .header("Idempotency-Key", key)
                                .contentType(APPLICATION_JSON)
                                .content(VALID))
                        .andReturn()
                        .getResponse()
                        .getStatus()));
            }
            long created = 0;
            long replayedOrInProgress = 0;
            for (Future<Integer> result : results) {
                int statusCode = result.get();
                if (statusCode == 201) created++;
                else if (statusCode == 200 || statusCode == 409) replayedOrInProgress++;
                else throw new AssertionError("unexpected " + statusCode);
            }
            assertThat(created).isEqualTo(1);
            assertThat(replayedOrInProgress).isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
        // and once settled, the key replays the single claim
        mockMvc.perform(post("/api/v1/claims")
                        .header("Authorization", bearer)
                        .header("X-Client-Id", "idem-par-after")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }
}
