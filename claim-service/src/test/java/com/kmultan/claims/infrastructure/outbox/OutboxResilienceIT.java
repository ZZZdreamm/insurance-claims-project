package com.kmultan.claims.infrastructure.outbox;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The broker goes away mid-flight. Writes must keep succeeding (the outbox
 * absorbs them), nothing may be lost, and once the broker is back every event
 * is relayed in order — the property that makes the outbox worth its table.
 */
class OutboxResilienceIT extends AbstractIntegrationTest {

    @Autowired ClaimService service;
    @Autowired OutboxRepository outbox;

    @Test
    void claimsSubmittedWhileKafkaIsDownAreRelayedWhenItReturns() throws Exception {
        await().atMost(Duration.ofSeconds(30)).until(() -> outbox.countByPublishedAtIsNull() == 0);   // start clean

        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        List<Claim> submitted = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                submitted.add(service.submit("POL-DOWN", "DN " + i, LocalDate.now(), "Submitted while the broker was paused #" + i, null, List.of()));
            }
            Thread.sleep(3000);   // several poller ticks
            for (Claim c : submitted) {
                assertThat(outbox.findByAggregateIdOrderById(c.getId())).extracting(OutboxEvent::getPublishedAt).containsOnlyNulls();
            }
            assertThat(outbox.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(3);
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            for (Claim c : submitted) {
                assertThat(outbox.findByAggregateIdOrderById(c.getId()).get(0).getPublishedAt()).isNotNull();
            }
        });
        // and the choreography resumes: the fake assessment answers, claims reach review
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            for (Claim c : submitted) {
                assertThat(service.get(c.getId()).getStatus()).isEqualTo(com.kmultan.claims.domain.ClaimStatus.PENDING_REVIEW);
            }
        });
    }
}
