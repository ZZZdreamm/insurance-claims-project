package com.kmultan.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Saga throughput and idempotency under load: N approvals, every one delivered
 * twice (as a crashing consumer would see it), must yield exactly N payouts.
 * Run with {@code mvn verify -Dperf}.
 */
@Tag("perf")
@SpringBootTest
class PayoutThroughputIT {

    @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @ServiceConnection static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");
    static { POSTGRES.start(); KAFKA.start(); }

    static final int N = 200;

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired PayoutRepository payouts;

    @Test
    void twoHundredApprovalsDeliveredTwiceProduceExactlyTwoHundredPayouts() throws Exception {
        Map<String, Object> props = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "perf-" + UUID.randomUUID(), "true");
        Consumer<String, String> events = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        events.subscribe(List.of("payout.events"));

        Map<String, String> claims = new HashMap<>();
        long start = System.currentTimeMillis();
        for (int i = 0; i < N; i++) {
            String claimId = UUID.randomUUID().toString(), eventId = UUID.randomUUID().toString();
            String body = """
                    {"eventId":"%s","eventType":"CLAIM_APPROVED","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                     "claim":{"claimNumber":"CLM-%d","policyNumber":"POL-1","approvedAmount":%d.00}}""".formatted(eventId, claimId, i, 100 + i);
            claims.put(claimId, eventId);
            kafka.send("claims.events", claimId, body);
            kafka.send("claims.events", claimId, body);   // redelivery
        }
        kafka.flush();

        Map<String, Integer> issuedPerClaim = new ConcurrentHashMap<>();
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            for (ConsumerRecord<String, String> r : events.poll(Duration.ofMillis(500))) {
                JsonNode n = json.readTree(r.value());
                if ("PAYOUT_ISSUED".equals(n.get("type").asText())) issuedPerClaim.merge(r.key(), 1, Integer::sum);
            }
            assertThat(issuedPerClaim).hasSize(N);
        });
        long wall = System.currentTimeMillis() - start;
        Thread.sleep(2000);   // let any stray duplicates surface
        for (ConsumerRecord<String, String> r : events.poll(Duration.ofMillis(500))) {
            JsonNode n = json.readTree(r.value());
            if ("PAYOUT_ISSUED".equals(n.get("type").asText())) issuedPerClaim.merge(r.key(), 1, Integer::sum);
        }
        events.close();

        assertThat(issuedPerClaim.values()).containsOnly(1);                      // never paid twice
        assertThat(payouts.count()).isEqualTo(N);
        assertThat(payouts.findAll()).extracting(Payout::getStatus).containsOnly(Payout.Status.ISSUED);
        System.out.printf("PERF %-32s %d approvals (x2 delivered) -> %d payouts, 2 saga steps each, in %d ms (%.1f payouts/s)%n",
                "payout saga", N, N, wall, N * 1000.0 / wall);
    }
}
