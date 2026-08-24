package com.kmultan.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class PayoutSagaIT {

    @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @ServiceConnection static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");
    static { POSTGRES.start(); KAFKA.start(); }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired FundReservationRepository reservations;
    @Autowired PayoutRepository payouts;
    @Autowired MockMvc mvc;

    Consumer<String, String> events;
    final List<JsonNode> collected = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "it-" + UUID.randomUUID(), "true");
        events = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        events.subscribe(List.of("payout.events"));
    }

    @AfterEach
    void close() { events.close(); }

    private UUID claimEvent(UUID claimId, String type, String amount) throws Exception {
        UUID eventId = UUID.randomUUID();
        claimEvent(eventId, claimId, type, amount);
        return eventId;
    }

    private void claimEvent(UUID eventId, UUID claimId, String type, String amount) throws Exception {
        String body = """
                {"eventId":"%s","eventType":"%s","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                 "claim":{"claimNumber":"CLM-1","policyNumber":"POL-1","plateNumber":"X","approvedAmount":%s,"status":"APPROVED","futureField":true}}
                """.formatted(eventId, type, claimId, amount);
        kafka.send("claims.events", claimId.toString(), body).get();
    }

    /** Types of payout events seen so far for the claim, in order (accumulates across calls). */
    private List<String> typesFor(UUID claimId, int expected) {
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            for (ConsumerRecord<String, String> r : events.poll(Duration.ofMillis(300))) {
                if (r.key().equals(claimId.toString())) collected.add(json.readTree(r.value()));
            }
            assertThat(collected).hasSizeGreaterThanOrEqualTo(expected);
        });
        return collected.stream().map(n -> n.get("type").asText()).toList();
    }

    @Test
    void approvalReservesThenPaysOut() throws Exception {
        UUID claim = UUID.randomUUID();
        UUID approval = claimEvent(claim, "CLAIM_APPROVED", "1200.00");

        assertThat(typesFor(claim, 2)).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED");
        assertThat(collected.get(0).get("causationEventId").asText()).isEqualTo(approval.toString());
        assertThat(collected.get(1).get("reference").asText()).startsWith("PAY-");
        assertThat(reservations.findById(claim).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.SETTLED);
        assertThat(payouts.findById(claim).orElseThrow().getStatus()).isEqualTo(Payout.Status.ISSUED);
    }

    @Test
    void redeliveredApprovalIsHandledOnce() throws Exception {
        UUID claim = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        claimEvent(eventId, claim, "CLAIM_APPROVED", "500.00");
        claimEvent(eventId, claim, "CLAIM_APPROVED", "500.00");   // consumer crashed after commit, before offset commit

        typesFor(claim, 2);
        Thread.sleep(1500);
        typesFor(claim, 2);
        assertThat(collected).extracting(n -> n.get("type").asText()).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED");
    }

    @Test
    void failedTransferCompensatesTheReservationAndCanBeRetried() throws Exception {
        UUID claim = UUID.randomUUID();
        claimEvent(claim, "CLAIM_APPROVED", "300.99");
        assertThat(typesFor(claim, 3)).containsExactly("FUNDS_RESERVED", "PAYOUT_FAILED", "FUNDS_RELEASED");
        assertThat(collected.get(1).get("reason").asText()).isEqualTo("Payment provider rejected the transfer");
        assertThat(reservations.findById(claim).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RELEASED);
        assertThat(payouts.findById(claim).orElseThrow().getStatus()).isEqualTo(Payout.Status.FAILED);

        // claim-service re-approves with a corrected amount: same rows, new attempt
        claimEvent(claim, "CLAIM_APPROVED", "301.00");
        assertThat(typesFor(claim, 5)).containsExactly("FUNDS_RESERVED", "PAYOUT_FAILED", "FUNDS_RELEASED", "FUNDS_RESERVED", "PAYOUT_ISSUED");
        assertThat(payouts.findById(claim).orElseThrow().getStatus()).isEqualTo(Payout.Status.ISSUED);
    }

    @Test
    void reservationAboveLimitIsRejected() throws Exception {
        UUID claim = UUID.randomUUID();
        claimEvent(claim, "CLAIM_APPROVED", "60000");
        assertThat(typesFor(claim, 1)).containsExactly("RESERVATION_REJECTED");
        assertThat(reservations.findById(claim)).isEmpty();
    }

    @Test
    void unacceptedPayoutIsReversed() throws Exception {
        UUID claim = UUID.randomUUID();
        claimEvent(claim, "CLAIM_APPROVED", "800.00");
        typesFor(claim, 2);
        claimEvent(claim, "PAYOUT_UNACCEPTED", "800.00");
        assertThat(typesFor(claim, 3)).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED", "PAYOUT_REVERSED");
        assertThat(payouts.findById(claim).orElseThrow().getStatus()).isEqualTo(Payout.Status.REVERSED);
        assertThat(reservations.findById(claim).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RELEASED);
    }

    @Test
    void poisonMessageGoesToDltAndCanBeReplayed() throws Exception {
        kafka.send("claims.events", UUID.randomUUID().toString(), "{not json").get();
        Thread.sleep(6000);   // 4 attempts with backoff, then DLT
        mvc.perform(post("/api/v1/dlq/replay")).andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("claims.events"))
                .andExpect(jsonPath("$.replayed").value(1));
    }
}
