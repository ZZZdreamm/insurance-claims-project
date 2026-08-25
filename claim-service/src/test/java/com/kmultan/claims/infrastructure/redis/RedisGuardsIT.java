package com.kmultan.claims.infrastructure.redis;

import com.kmultan.claims.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.infrastructure.security.JwtTokens;
import com.kmultan.claims.infrastructure.security.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Redis: client retries with the same Idempotency-Key never create a second claim; runaway clients get 429. */
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "claims.ratelimit.submit-per-minute=5")
class RedisGuardsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MutableClockConfig.MutableClock clock;
    @Autowired JwtTokens tokens;
    private String user() { return TestTokens.bearer(tokens, "anna", Role.POLICYHOLDER); }

    private static final String VALID = """
            {"policyNumber":"POL-RD","plateNumber":"RD 1","incidentDate":"%s",
             "description":"Redis guards integration test claim","estimatedAmount":100}
            """.formatted(LocalDate.now());

    @Test
    void sameIdempotencyKeyReplaysTheFirstClaim() throws Exception {
        String key = "mobile-" + UUID.randomUUID();
        String first = mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", "idem-test").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = first.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", "idem-test").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/v1/claims/" + id))
                .andExpect(jsonPath("$.id").value(id));

        // a different key is a different submission
        String second = mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", "idem-test").header("Idempotency-Key", "mobile-" + UUID.randomUUID())
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(second.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")).isNotEqualTo(id);
    }

    @Test
    void failedSubmissionDoesNotBurnTheKey() throws Exception {
        String key = "mobile-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", "idem-test2").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content("{\"policyNumber\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", "idem-test2").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", "idem-test2").header("Idempotency-Key", "bad key!").contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submissionsAreRateLimitedPerClient() throws Exception {
        String client = "burst-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", client).contentType(APPLICATION_JSON).content(VALID))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(4 - i)));
        }
        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", client).contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too many submissions"));
        // other clients are unaffected
        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", "other-" + UUID.randomUUID()).contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());
    }

    @Test
    void limitResetsWhenTheWindowRollsOver() throws Exception {
        String client = "window-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", client).contentType(APPLICATION_JSON).content(VALID))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", client).contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests());

        clock.advanceSeconds(60);   // next fixed window
        mvc.perform(post("/api/v1/claims").header("Authorization", user()).header("X-Client-Id", client).contentType(APPLICATION_JSON).content(VALID))
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
            for (int i = 0; i < 40; i++) {
                results.add(pool.submit((Callable<Integer>) () ->
                        mvc.perform(post("/api/v1/claims").header("Authorization", bearer).header("X-Client-Id", client)
                                .contentType(APPLICATION_JSON).content(VALID)).andReturn().getResponse().getStatus()));
            }
            long created = 0, limited = 0;
            for (Future<Integer> f : results) {
                int code = f.get();
                if (code == 201) created++; else if (code == 429) limited++; else throw new AssertionError("unexpected " + code);
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
            for (int i = 0; i < 10; i++) {
                String client = "idem-par-" + i;   // separate rate-limit buckets: only idempotency is under test
                results.add(pool.submit((Callable<Integer>) () ->
                        mvc.perform(post("/api/v1/claims").header("Authorization", bearer).header("X-Client-Id", client).header("Idempotency-Key", key)
                                .contentType(APPLICATION_JSON).content(VALID)).andReturn().getResponse().getStatus()));
            }
            long created = 0, replayedOrInProgress = 0;
            for (Future<Integer> f : results) {
                int code = f.get();
                if (code == 201) created++; else if (code == 200 || code == 409) replayedOrInProgress++; else throw new AssertionError("unexpected " + code);
            }
            assertThat(created).isEqualTo(1);
            assertThat(replayedOrInProgress).isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
        // and once settled, the key replays the single claim
        mvc.perform(post("/api/v1/claims").header("Authorization", bearer).header("X-Client-Id", "idem-par-after").header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
    }
}
