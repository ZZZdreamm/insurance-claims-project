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

    /**
     * The broker advertises the proxy's address for its extra listener, so every client hop goes
     * through Toxiproxy. The listener's host part MUST be the broker's own alias: Testcontainers
     * binds the listener to that host and adds it as a network alias of the Kafka container, so a
     * foreign name here (e.g. "toxiproxy") collides with the proxy's alias in Docker DNS and the
     * broker randomly binds the wrong IP — the listener then never comes up.
     */
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0")
            .withNetwork(NETWORK)
            .withListener(
                    "kafka:" + KAFKA_PROXY_LISTENER_PORT,
                    () -> TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(TOXIPROXY_FIRST_PORT));

    static final Proxy KAFKA_PROXY;

    static {
        POSTGRES.start();
        TOXIPROXY.start();
        // the broker must be up (and its network alias registered) before the proxy points at it,
        // or the first upstream dials can fail and every client connection dies at the handshake
        KAFKA.start();
        KAFKA_PROXY = reachableKafkaProxy();
    }

    /**
     * Creates the proxy and proves a Kafka client can get through it before Spring boots — a broken
     * proxied path otherwise surfaces as two 90-second test timeouts with no useful message. If the
     * first attempt cannot reach the broker, the proxy is recreated once in case it latched onto a
     * dead upstream.
     */
    private static Proxy reachableKafkaProxy() {
        try {
            ToxiproxyClient toxiproxyClient = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            Proxy proxy = toxiproxyClient.createProxy(
                    "kafka", "0.0.0.0:" + TOXIPROXY_FIRST_PORT, "kafka:" + KAFKA_PROXY_LISTENER_PORT);
            if (brokerReachableThroughProxy(Duration.ofSeconds(30))) {
                return proxy;
            }
            proxy.delete();
            proxy = toxiproxyClient.createProxy(
                    "kafka", "0.0.0.0:" + TOXIPROXY_FIRST_PORT, "kafka:" + KAFKA_PROXY_LISTENER_PORT);
            if (!brokerReachableThroughProxy(Duration.ofSeconds(60))) {
                throw new IllegalStateException(diagnoseProxiedPath());
            }
            return proxy;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create the Kafka proxy", exception);
        }
    }

    /** Which leg is broken: the broker itself, the TCP hop to the proxy, or the proxy's upstream dial. */
    private static String diagnoseProxiedPath() {
        StringBuilder report = new StringBuilder("Kafka is unreachable through Toxiproxy.\n");
        java.util.Properties directProperties = new java.util.Properties();
        directProperties.put(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        directProperties.put(org.apache.kafka.clients.admin.AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        try (org.apache.kafka.clients.admin.Admin admin =
                org.apache.kafka.clients.admin.Admin.create(directProperties)) {
            report.append("Direct broker probe (")
                    .append(KAFKA.getBootstrapServers())
                    .append("): nodes=")
                    .append(admin.describeCluster().nodes().get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .append('\n');
        } catch (Exception directProbeFailure) {
            report.append("Direct broker probe FAILED: ")
                    .append(directProbeFailure)
                    .append('\n');
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(
                    new java.net.InetSocketAddress(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(TOXIPROXY_FIRST_PORT)),
                    3000);
            report.append("Raw TCP connect to the proxy port: OK\n");
        } catch (IOException tcpFailure) {
            report.append("Raw TCP connect to the proxy port FAILED: ")
                    .append(tcpFailure)
                    .append('\n');
        }
        report.append("--- toxiproxy logs ---\n").append(tail(TOXIPROXY.getLogs(), 40));
        report.append("--- kafka logs ---\n").append(tail(KAFKA.getLogs(), 60));
        return report.toString();
    }

    private static String tail(String logs, int lines) {
        List<String> allLines = logs.lines().toList();
        return String.join("\n", allLines.subList(Math.max(0, allLines.size() - lines), allLines.size())) + "\n";
    }

    /**
     * Plain retry loop on the calling thread. This runs inside the class's static initialiser, so
     * awaitility must NOT be used here: its condition thread would invoke a lambda belonging to this
     * class, block on the class-initialisation lock held by the thread running this very code, and
     * the condition would never evaluate — a guaranteed deadlock dressed up as a timeout.
     */
    private static boolean brokerReachableThroughProxy(Duration timeout) {
        String proxiedBootstrap = TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(TOXIPROXY_FIRST_PORT);
        java.util.Properties adminProperties = new java.util.Properties();
        adminProperties.put(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, proxiedBootstrap);
        adminProperties.put(org.apache.kafka.clients.admin.AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        long deadline = System.nanoTime() + timeout.toNanos();
        try (org.apache.kafka.clients.admin.Admin admin =
                org.apache.kafka.clients.admin.Admin.create(adminProperties)) {
            while (System.nanoTime() < deadline) {
                try {
                    if (!admin.describeCluster()
                            .nodes()
                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                            .isEmpty()) {
                        return true;
                    }
                } catch (Exception notReachableYet) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
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

        // the HTTP path never waits for Kafka: the outbox absorbs the slow broker. A synchronous
        // publish-and-consume would cost several 1.5s round trips; the bound stays generous because
        // CI runners make even a plain HTTP+DB call slow, and the decoupling is what is under test.
        assertThat(submitMillis).isLessThan(3000);
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
