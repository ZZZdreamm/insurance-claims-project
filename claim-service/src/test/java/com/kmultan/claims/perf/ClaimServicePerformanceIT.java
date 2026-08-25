package com.kmultan.claims.perf;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.infrastructure.outbox.OutboxRepository;
import com.kmultan.claims.infrastructure.security.JwtTokens;
import com.kmultan.claims.infrastructure.security.TestTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Performance smoke on real Postgres/Kafka/Redis via Testcontainers. Thresholds
 * are deliberately loose (a laptop under Docker Desktop is not a benchmark
 * rig); the point is a repeatable number to compare before/after a change.
 * Run with {@code mvn verify -Dperf}; excluded from the default build.
 */
@Tag("perf")
@AutoConfigureMockMvc
@TestPropertySource(properties = "claims.ratelimit.submit-per-minute=1000000")
class ClaimServicePerformanceIT extends AbstractIntegrationTest {

    static final int THREADS = 20;
    static final int PER_THREAD = 25;

    @Autowired MockMvc mvc;
    @Autowired JwtTokens tokens;
    @Autowired ClaimService service;
    @Autowired OutboxRepository outbox;

    private static final String BODY = """
            {"policyNumber":"POL-PERF","plateNumber":"PF 1","incidentDate":"%s",
             "description":"Performance run: rear bumper scratched in a car park","estimatedAmount":400}
            """.formatted(LocalDate.now());

    @Test
    void submitLatencyUnderConcurrency_andOutboxDrains() throws Exception {
        String bearer = TestTokens.bearer(tokens, "anna", Role.POLICYHOLDER);
        Stats http = new Stats();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long start = System.currentTimeMillis();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                results.add(pool.submit((Callable<Integer>) () -> {
                    int ok = 0;
                    for (int i = 0; i < PER_THREAD; i++) {
                        long s = System.nanoTime();
                        int code = mvc.perform(post("/api/v1/claims").header("Authorization", bearer)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(APPLICATION_JSON).content(BODY))
                                .andReturn().getResponse().getStatus();
                        http.record((System.nanoTime() - s) / 1_000_000);
                        if (code == 201) ok++;
                    }
                    return ok;
                }));
            }
            int created = 0;
            for (Future<Integer> f : results) created += f.get();
            long wall = System.currentTimeMillis() - start;
            System.out.println("PERF " + http.summary("POST /api/v1/claims (" + THREADS + " threads)", wall));
            assertThat(created).isEqualTo(THREADS * PER_THREAD);
            assertThat(http.percentile(95)).as("p95 submit latency").isLessThan(1000);
        } finally {
            pool.shutdownNow();
        }

        // outbox relay: everything above (+ the assessment events the fakes trigger) must drain quickly
        long drainStart = System.currentTimeMillis();
        await().atMost(Duration.ofSeconds(60)).until(() -> outbox.countByPublishedAtIsNull() == 0);
        long drainMillis = System.currentTimeMillis() - drainStart;
        System.out.printf("PERF %-32s pending drained in %d ms (poll interval 1000 ms, batch 100)%n", "outbox relay", drainMillis);
        assertThat(drainMillis).isLessThan(30_000);
    }

    @Test
    void choreographyThroughput_claimsReachReview() {
        int n = 50;
        List<Claim> claims = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            claims.add(service.submit("POL-CT", "CT " + i, LocalDate.now(), "Choreography throughput claim #" + i, null, List.of()));
        }
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(claims.stream().filter(c -> service.get(c.getId()).getStatus() == ClaimStatus.PENDING_REVIEW).count()).isEqualTo(n));
        long wall = System.currentTimeMillis() - start;
        System.out.printf("PERF %-32s %d claims submitted -> triaged (2 Kafka hops each) in %d ms (%.1f claims/s)%n",
                "submit -> PENDING_REVIEW", n, wall, n * 1000.0 / wall);
        assertThat(wall).isLessThan(60_000);
    }
}
