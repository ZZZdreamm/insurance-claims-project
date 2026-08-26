package com.kmultan.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;
import com.kmultan.platform.kafka.KafkaTestConsumer;
import com.kmultan.platform.security.TestJwtTokenFactory;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class PayoutSagaIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();
    private static final String CLAIMS_TOPIC = "claims.events";
    private static final String PAYOUT_TOPIC = "payout.events";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FundReservationRepository reservations;

    @Autowired
    PayoutRepository payouts;

    @Autowired
    MockMvc mockMvc;

    KafkaTestConsumer payoutEvents;

    @BeforeEach
    void subscribe() {
        payoutEvents = new KafkaTestConsumer(KAFKA.getBootstrapServers(), PAYOUT_TOPIC);
    }

    @AfterEach
    void close() {
        payoutEvents.close();
    }

    private UUID publishClaimEvent(UUID claimId, String eventType, String amount) throws Exception {
        UUID eventId = UUID.randomUUID();
        publishClaimEvent(eventId, claimId, eventType, amount);
        return eventId;
    }

    private void publishClaimEvent(UUID eventId, UUID claimId, String eventType, String amount) throws Exception {
        String body =
                """
                {"eventId":"%s","eventType":"%s","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                 "claim":{"claimNumber":"CLM-1","policyNumber":"POL-1","plateNumber":"X",
                          "approvedAmount":%s,"status":"APPROVED","futureField":true}}
                """
                        .formatted(eventId, eventType, claimId, amount);
        kafkaTemplate.send(CLAIMS_TOPIC, claimId.toString(), body).get();
    }

    private JsonNode payload(ConsumerRecord<String, String> consumerRecord) {
        try {
            return objectMapper.readTree(consumerRecord.value());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Payout event types seen so far for the claim, in order. */
    private List<String> eventTypesFor(UUID claimId, int expectedAtLeast) {
        await().atMost(Duration.ofSeconds(25))
                .untilAsserted(() -> assertThat(payoutEvents.pollForKey(claimId.toString()))
                        .hasSizeGreaterThanOrEqualTo(expectedAtLeast));
        return payoutEvents.pollForKey(claimId.toString()).stream()
                .map(consumerRecord -> payload(consumerRecord).get("type").asText())
                .toList();
    }

    private JsonNode eventAt(UUID claimId, int index) {
        return payload(payoutEvents.pollForKey(claimId.toString()).get(index));
    }

    @Test
    void approvalReservesThenPaysOut() throws Exception {
        UUID claimId = UUID.randomUUID();
        UUID approvalEventId = publishClaimEvent(claimId, "CLAIM_APPROVED", "1200.00");

        assertThat(eventTypesFor(claimId, 2)).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED");
        assertThat(eventAt(claimId, 0).get("causationEventId").asText()).isEqualTo(approvalEventId.toString());
        assertThat(eventAt(claimId, 1).get("reference").asText()).startsWith("PAY-");
        assertThat(reservations.findById(claimId).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.SETTLED);
        assertThat(payouts.findById(claimId).orElseThrow().getStatus()).isEqualTo(Payout.Status.ISSUED);
    }

    @Test
    void redeliveredApprovalIsHandledOnce() throws Exception {
        UUID claimId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        publishClaimEvent(eventId, claimId, "CLAIM_APPROVED", "500.00");
        publishClaimEvent(
                eventId, claimId, "CLAIM_APPROVED", "500.00"); // consumer crashed after commit, before offset commit

        eventTypesFor(claimId, 2);
        Thread.sleep(1500);
        assertThat(eventTypesFor(claimId, 2)).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED");
    }

    @Test
    void failedTransferCompensatesTheReservationAndCanBeRetried() throws Exception {
        UUID claimId = UUID.randomUUID();
        publishClaimEvent(claimId, "CLAIM_APPROVED", "300.99");
        assertThat(eventTypesFor(claimId, 3)).containsExactly("FUNDS_RESERVED", "PAYOUT_FAILED", "FUNDS_RELEASED");
        assertThat(eventAt(claimId, 1).get("reason").asText()).isEqualTo("Payment provider rejected the transfer");
        assertThat(reservations.findById(claimId).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RELEASED);
        assertThat(payouts.findById(claimId).orElseThrow().getStatus()).isEqualTo(Payout.Status.FAILED);

        // claim-service re-approves with a corrected amount: same rows, new attempt
        publishClaimEvent(claimId, "CLAIM_APPROVED", "301.00");
        assertThat(eventTypesFor(claimId, 5))
                .containsExactly(
                        "FUNDS_RESERVED", "PAYOUT_FAILED", "FUNDS_RELEASED", "FUNDS_RESERVED", "PAYOUT_ISSUED");
        assertThat(payouts.findById(claimId).orElseThrow().getStatus()).isEqualTo(Payout.Status.ISSUED);
    }

    @Test
    void reservationAboveLimitIsRejected() throws Exception {
        UUID claimId = UUID.randomUUID();
        publishClaimEvent(claimId, "CLAIM_APPROVED", "60000");
        assertThat(eventTypesFor(claimId, 1)).containsExactly("RESERVATION_REJECTED");
        assertThat(reservations.findById(claimId)).isEmpty();
    }

    @Test
    void unacceptedPayoutIsReversed() throws Exception {
        UUID claimId = UUID.randomUUID();
        publishClaimEvent(claimId, "CLAIM_APPROVED", "800.00");
        eventTypesFor(claimId, 2);
        publishClaimEvent(claimId, "PAYOUT_UNACCEPTED", "800.00");
        assertThat(eventTypesFor(claimId, 3)).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED", "PAYOUT_REVERSED");
        assertThat(payouts.findById(claimId).orElseThrow().getStatus()).isEqualTo(Payout.Status.REVERSED);
        assertThat(reservations.findById(claimId).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RELEASED);
    }

    @Test
    void poisonMessageGoesToDltAndCanBeReplayedByFinanceOnly() throws Exception {
        kafkaTemplate
                .send(CLAIMS_TOPIC, UUID.randomUUID().toString(), "{not json")
                .get();
        Thread.sleep(6000); // 4 attempts with backoff, then DLT
        mockMvc.perform(post("/api/v1/dlq/replay").header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/dlq/replay")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/dlq/replay").header("Authorization", TOKENS.bearer("finance", "FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value(CLAIMS_TOPIC))
                .andExpect(jsonPath("$.replayed").value(1));
    }
}
