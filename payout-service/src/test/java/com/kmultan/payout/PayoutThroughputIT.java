package com.kmultan.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;
import com.kmultan.platform.kafka.KafkaTestConsumer;

/**
 * Saga throughput and idempotency under load: N approvals, every one delivered
 * twice (as a crashing consumer would see it), must yield exactly N payouts.
 * Run with {@code mvn verify -Dperf}.
 */
@Tag("perf")
@SpringBootTest
class PayoutThroughputIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private static final int APPROVALS = 200;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PayoutRepository payouts;

    private boolean isPayoutIssued(ConsumerRecord<String, String> consumerRecord) {
        try {
            return "PAYOUT_ISSUED"
                    .equals(objectMapper
                            .readTree(consumerRecord.value())
                            .get("type")
                            .asText());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void approvalsDeliveredTwiceProduceExactlyOnePayoutEach() throws Exception {
        try (KafkaTestConsumer payoutEvents = new KafkaTestConsumer(KAFKA.getBootstrapServers(), "payout.events")) {
            long startedAtMillis = System.currentTimeMillis();
            for (int index = 0; index < APPROVALS; index++) {
                String claimId = UUID.randomUUID().toString();
                String body =
                        """
                        {"eventId":"%s","eventType":"CLAIM_APPROVED","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                         "claim":{"claimNumber":"CLM-%d","policyNumber":"POL-1","approvedAmount":%d.00}}"""
                                .formatted(UUID.randomUUID(), claimId, index, 100 + index);
                kafkaTemplate.send("claims.events", claimId, body);
                kafkaTemplate.send("claims.events", claimId, body); // redelivery
            }
            kafkaTemplate.flush();

            await().atMost(Duration.ofSeconds(120))
                    .untilAsserted(() -> assertThat(payoutEvents.poll(this::isPayoutIssued).stream()
                                    .map(ConsumerRecord::key)
                                    .distinct()
                                    .count())
                            .isEqualTo(APPROVALS));
            long wallMillis = System.currentTimeMillis() - startedAtMillis;

            Thread.sleep(2000); // let any stray duplicates surface
            Map<String, Long> issuedPerClaim = payoutEvents.poll(this::isPayoutIssued).stream()
                    .collect(Collectors.groupingBy(ConsumerRecord::key, Collectors.counting()));
            assertThat(issuedPerClaim.values()).containsOnly(1L); // never paid twice
            assertThat(payouts.count()).isEqualTo(APPROVALS);
            assertThat(payouts.findAll()).extracting(Payout::getStatus).containsOnly(Payout.Status.ISSUED);
            System.out.printf(
                    "PERF %-32s %d approvals (x2 delivered) -> %d payouts, 2 saga steps each, in %d ms (%.1f payouts/s)%n",
                    "payout saga", APPROVALS, APPROVALS, wallMillis, APPROVALS * 1000.0 / wallMillis);
        }
    }
}
