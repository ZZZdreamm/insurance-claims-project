package com.kmultan.claims.perf;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.platform.outbox.OutboxEventRepository;
import com.kmultan.platform.security.TestJwtTokenFactory;
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

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();
    private static final int THREADS = 20;
    private static final int SUBMITS_PER_THREAD = 25;
    private static final int CHOREOGRAPHY_CLAIMS = 50;

    @Autowired MockMvc mockMvc;
    @Autowired ClaimService claimService;
    @Autowired OutboxEventRepository outboxEvents;

    private static final String BODY = """
            {"policyNumber":"POL-PERF","plateNumber":"PF 1","incidentDate":"%s",
             "description":"Performance run: rear bumper scratched in a car park","estimatedAmount":400}
            """.formatted(LocalDate.now());

    @Test
    void submitLatencyUnderConcurrency_andOutboxDrains() throws Exception {
        String bearer = TOKENS.bearer("anna", "POLICYHOLDER");
        LatencyStatistics httpLatency = new LatencyStatistics();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long startedAtMillis = System.currentTimeMillis();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                results.add(pool.submit((Callable<Integer>) () -> {
                    int created = 0;
                    for (int submission = 0; submission < SUBMITS_PER_THREAD; submission++) {
                        long requestStartedAt = System.nanoTime();
                        int statusCode = mockMvc.perform(post("/api/v1/claims").header("Authorization", bearer)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(APPLICATION_JSON).content(BODY))
                                .andReturn().getResponse().getStatus();
                        httpLatency.record((System.nanoTime() - requestStartedAt) / 1_000_000);
                        if (statusCode == 201) created++;
                    }
                    return created;
                }));
            }
            int createdTotal = 0;
            for (Future<Integer> result : results) createdTotal += result.get();
            long wallMillis = System.currentTimeMillis() - startedAtMillis;
            System.out.println("PERF " + httpLatency.summary("POST /api/v1/claims (" + THREADS + " threads)", wallMillis));
            assertThat(createdTotal).isEqualTo(THREADS * SUBMITS_PER_THREAD);
            assertThat(httpLatency.percentile(95)).as("p95 submit latency").isLessThan(1000);
        } finally {
            pool.shutdownNow();
        }

        // outbox relay: everything above (+ the assessment events the fakes trigger) must drain quickly
        long drainStartedAt = System.currentTimeMillis();
        await().atMost(Duration.ofSeconds(60)).until(() -> outboxEvents.countByPublishedAtIsNull() == 0);
        long drainMillis = System.currentTimeMillis() - drainStartedAt;
        System.out.printf("PERF %-32s pending drained in %d ms (poll interval 1000 ms, batch 100)%n", "outbox relay", drainMillis);
        assertThat(drainMillis).isLessThan(30_000);
    }

    @Test
    void choreographyThroughput_claimsReachReview() {
        List<Claim> claims = new ArrayList<>();
        long startedAtMillis = System.currentTimeMillis();
        for (int index = 0; index < CHOREOGRAPHY_CLAIMS; index++) {
            claims.add(claimService.submit("POL-CT", "CT " + index, LocalDate.now(), "Choreography throughput claim #" + index, null, List.of()));
        }
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(claims.stream().filter(claim -> claimService.get(claim.getId()).getStatus() == ClaimStatus.PENDING_REVIEW).count())
                        .isEqualTo(CHOREOGRAPHY_CLAIMS));
        long wallMillis = System.currentTimeMillis() - startedAtMillis;
        System.out.printf("PERF %-32s %d claims submitted -> triaged (2 Kafka hops each) in %d ms (%.1f claims/s)%n",
                "submit -> PENDING_REVIEW", CHOREOGRAPHY_CLAIMS, wallMillis, CHOREOGRAPHY_CLAIMS * 1000.0 / wallMillis);
        assertThat(wallMillis).isLessThan(60_000);
    }
}
