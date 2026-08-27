package com.kmultan.claims.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.kafka.KafkaContainer;

import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.infrastructure.consumers.FakeDownstreamServices;
import com.kmultan.platform.outbox.OutboxEvent;
import com.kmultan.platform.outbox.OutboxEventRepository;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;

/**
 * Network-fault injection between the services and Kafka: the whole application
 * talks to the broker through a Toxiproxy TCP proxy, and the test cuts or
 * degrades that link at runtime. Unlike pausing the container
 * ({@code OutboxResilienceIT}), this exercises the realistic failure mode —
 * connections that hang or drop while the broker itself is healthy.
 */
@Tag("integration")
@SpringBootTest
@Import(FakeDownstreamServices.class)
class ToxiproxyResilienceIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final int KAFKA_PROXY_LISTENER_PORT = 19092;
    private static final int TOXIPROXY_FIRST_PORT = 8666;

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.11.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("toxiproxy");

    /** The broker advertises the proxy's address for its extra listener, so every client hop goes through Toxiproxy. */
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener(
                    "toxiproxy:" + KAFKA_PROXY_LISTENER_PORT,
                    () -> TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(TOXIPROXY_FIRST_PORT));

    static final Proxy KAFKA_PROXY;

    static {
        POSTGRES.start();
        TOXIPROXY.start();
        try {
            ToxiproxyClient toxiproxyClient = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            KAFKA_PROXY = toxiproxyClient.createProxy(
                    "kafka", "0.0.0.0:" + TOXIPROXY_FIRST_PORT, "kafka:" + KAFKA_PROXY_LISTENER_PORT);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create the Kafka proxy", exception);
        }
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaThroughProxy(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.kafka.bootstrap-servers",
                () -> TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(TOXIPROXY_FIRST_PORT));
    }

    @Autowired
    ClaimService claimService;

    @Autowired
    OutboxEventRepository outboxEvents;

    @AfterEach
    void healTheNetwork() throws IOException {
        KAFKA_PROXY.toxics().getAll().forEach(toxic -> {
            try {
                toxic.remove();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        KAFKA_PROXY.enable();
    }

    private Claim submit(String description) {
        return claimService.submit("POL-TX", "TX 1", LocalDate.now(), description, new BigDecimal("400"), List.of());
    }

    @Test
    void brokerLatencyDelaysNothingButTheRelay() throws Exception {
        KAFKA_PROXY.toxics().latency("slow-kafka", ToxicDirection.DOWNSTREAM, 1500);

        long submitStartedAt = System.currentTimeMillis();
        Claim claim = submit("Toxiproxy latency test: cracked windscreen");
        long submitMillis = System.currentTimeMillis() - submitStartedAt;

        // the HTTP path never waits for Kafka: the outbox absorbs the slow broker
        assertThat(submitMillis).isLessThan(1000);
        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(() ->
                        assertThat(claimService.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW));
    }

    @Test
    void severedConnectionLosesNothingAndRecovers() throws Exception {
        await().atMost(Duration.ofSeconds(60)).until(() -> outboxEvents.countByPublishedAtIsNull() == 0);
        KAFKA_PROXY.disable();

        Claim claim = submit("Toxiproxy outage test: dented door");
        Thread.sleep(3000); // several relay ticks against a dead connection

        assertThat(outboxEvents.findByAggregateIdOrderById(claim.getId()))
                .extracting(OutboxEvent::getPublishedAt)
                .containsOnlyNulls();

        KAFKA_PROXY.enable();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(outboxEvents
                            .findByAggregateIdOrderById(claim.getId())
                            .get(0)
                            .getPublishedAt())
                    .isNotNull();
            assertThat(claimService.get(claim.getId()).getStatus()).isEqualTo(ClaimStatus.PENDING_REVIEW);
        });
    }
}
