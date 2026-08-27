package com.kmultan.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.application.PayoutSaga;
import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;
import com.kmultan.platform.kafka.KafkaTestConsumer;

/**
 * The asynchronous provider path: the saga hands the transfer to an external
 * gateway (played here by a controller inside the test's own server), keeps the
 * payout PENDING with the reservation held, and settles or compensates on the
 * webhook — or via the timeout sweep when the provider never answers.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "server.port=18095",
            "payout.gateway.mode=async",
            "payout.gateway.url=http://localhost:18095/fake-gateway",
            "payout.gateway.callback-base-url=http://localhost:18095",
            "payout.gateway.callback-token=test-gateway-token",
            "payout.gateway.pending-timeout=PT30S",
            "payout.gateway.timeout-sweep-interval-ms=60000"
        })
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AsynchronousGatewayIT.FakeGatewayConfiguration.class)
class AsynchronousGatewayIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @TestConfiguration
    static class FakeGatewayConfiguration {

        @Bean
        FakeGatewayController fakeGatewayController() {
            return new FakeGatewayController();
        }
    }

    /** Records what the saga sends and answers 202 ACCEPTED, like the real simulator — but never calls back on its own. */
    @RestController
    static class FakeGatewayController {

        final ConcurrentLinkedQueue<Map<String, Object>> transferRequests = new ConcurrentLinkedQueue<>();

        @PostMapping("/fake-gateway/transfers")
        ResponseEntity<Map<String, String>> accept(@RequestBody Map<String, Object> request) {
            transferRequests.add(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("transferId", UUID.randomUUID().toString(), "status", "ACCEPTED"));
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FundReservationRepository reservations;

    @Autowired
    PayoutRepository payouts;

    @Autowired
    PayoutSaga payoutSaga;

    @Autowired
    FakeGatewayController fakeGateway;

    @Autowired
    MockMvc mockMvc;

    KafkaTestConsumer payoutEvents;

    @BeforeEach
    void subscribe() {
        payoutEvents = new KafkaTestConsumer(KAFKA.getBootstrapServers(), "payout.events");
    }

    @AfterEach
    void close() {
        payoutEvents.close();
    }

    private UUID approveClaim(UUID claimId, String amount) throws Exception {
        UUID eventId = UUID.randomUUID();
        String body =
                """
                {"eventId":"%s","eventType":"CLAIM_APPROVED","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                 "claim":{"claimNumber":"CLM-1","policyNumber":"POL-1","plateNumber":"X",
                          "approvedAmount":%s,"status":"APPROVED"}}
                """
                        .formatted(eventId, claimId, amount);
        kafkaTemplate.send("claims.events", claimId.toString(), body).get();
        return eventId;
    }

    private Payout awaitPendingWithReference(UUID claimId) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                        payouts.findById(claimId).map(Payout::getReference).orElse(null))
                .isNotNull());
        Payout payout = payouts.findById(claimId).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(Payout.Status.PENDING);
        return payout;
    }

    private void callback(String transferId, String callbackStatus, String token, int expectedHttpStatus)
            throws Exception {
        mockMvc.perform(post("/api/v1/payments/callback")
                        .header("X-Gateway-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transferId", transferId,
                                "status", callbackStatus,
                                "reason", "REJECTED".equals(callbackStatus) ? "Insufficient funds" : ""))))
                .andExpect(status().is(expectedHttpStatus));
    }

    private List<String> eventTypesFor(UUID claimId, int expectedAtLeast) {
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(payoutEvents.pollForKey(claimId.toString()))
                        .hasSizeGreaterThanOrEqualTo(expectedAtLeast));
        return payoutEvents.pollForKey(claimId.toString()).stream()
                .map(consumerRecord -> readType(consumerRecord))
                .toList();
    }

    private String readType(ConsumerRecord<String, String> consumerRecord) {
        try {
            JsonNode payload = objectMapper.readTree(consumerRecord.value());
            return payload.get("type").asText();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void webhookCompletionSettlesTheReservation() throws Exception {
        UUID claimId = UUID.randomUUID();
        approveClaim(claimId, "1200.00");

        Payout payout = awaitPendingWithReference(claimId);
        assertThat(fakeGateway.transferRequests)
                .anySatisfy(request -> assertThat(request.get("claimId")).isEqualTo(claimId.toString()));
        assertThat(reservations.findById(claimId).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RESERVED);

        callback(payout.getReference(), "COMPLETED", "test-gateway-token", 204);

        assertThat(eventTypesFor(claimId, 2)).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED");
        assertThat(payouts.findById(claimId).orElseThrow().getStatus()).isEqualTo(Payout.Status.ISSUED);
        assertThat(reservations.findById(claimId).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.SETTLED);

        // a redelivered webhook is acknowledged but changes nothing
        callback(payout.getReference(), "COMPLETED", "test-gateway-token", 204);
        assertThat(eventTypesFor(claimId, 2)).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED");
    }

    @Test
    void webhookRejectionCompensates() throws Exception {
        UUID claimId = UUID.randomUUID();
        approveClaim(claimId, "600.00");
        Payout payout = awaitPendingWithReference(claimId);

        callback(payout.getReference(), "REJECTED", "test-gateway-token", 204);

        assertThat(eventTypesFor(claimId, 3)).containsExactly("FUNDS_RESERVED", "PAYOUT_FAILED", "FUNDS_RELEASED");
        assertThat(payouts.findById(claimId).orElseThrow().getStatus()).isEqualTo(Payout.Status.FAILED);
        assertThat(reservations.findById(claimId).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RELEASED);
    }

    @Test
    void webhookWithoutTheSharedTokenIsRejected() throws Exception {
        callback(UUID.randomUUID().toString(), "COMPLETED", "wrong-token", 401);
    }

    @Test
    void unconfirmedTransferTimesOutAndCompensates() throws Exception {
        UUID claimId = UUID.randomUUID();
        approveClaim(claimId, "900.00");
        awaitPendingWithReference(claimId);

        int failedCount = payoutSaga.failTimedOutPayouts(Instant.now().plusSeconds(1));

        assertThat(failedCount).isGreaterThanOrEqualTo(1);
        assertThat(eventTypesFor(claimId, 3)).containsExactly("FUNDS_RESERVED", "PAYOUT_FAILED", "FUNDS_RELEASED");
        assertThat(reservations.findById(claimId).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RELEASED);
    }
}
