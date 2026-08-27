package com.kmultan.claims.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.kmultan.claims.AbstractIntegrationTest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.platform.outbox.OutboxEvent;
import com.kmultan.platform.outbox.OutboxEventRepository;

/**
 * The broker goes away mid-flight. Writes must keep succeeding (the outbox
 * absorbs them), nothing may be lost, and once the broker is back every event
 * is relayed in order — the property that makes the outbox worth its table.
 */
class OutboxResilienceIT extends AbstractIntegrationTest {

    @Autowired
    ClaimService claimService;

    @Autowired
    OutboxEventRepository outboxEvents;

    @Test
    void claimsSubmittedWhileKafkaIsDownAreRelayedWhenItReturns() throws Exception {
        await().atMost(Duration.ofSeconds(90)).until(() -> outboxEvents.countByPublishedAtIsNull() == 0); // start clean

        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        List<Claim> submitted = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                submitted.add(claimService.submit(
                        "POL-DOWN",
                        "DN " + index,
                        LocalDate.now(),
                        "Submitted while the broker was paused #" + index,
                        null,
                        List.of()));
            }
            Thread.sleep(3000); // several poller ticks
            for (Claim claim : submitted) {
                assertThat(outboxEvents.findByAggregateIdOrderById(claim.getId()))
                        .extracting(OutboxEvent::getPublishedAt)
                        .containsOnlyNulls();
            }
            assertThat(outboxEvents.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(3);
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            for (Claim claim : submitted) {
                assertThat(outboxEvents
                                .findByAggregateIdOrderById(claim.getId())
                                .get(0)
                                .getPublishedAt())
                        .isNotNull();
            }
        });
        // and the choreography resumes: the fake assessment answers, claims reach review
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            for (Claim claim : submitted) {
                assertThat(claimService.get(claim.getId()).getStatus())
                        .isEqualTo(com.kmultan.claims.domain.ClaimStatus.PENDING_REVIEW);
            }
        });
    }
}
