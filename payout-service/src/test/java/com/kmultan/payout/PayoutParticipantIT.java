package com.kmultan.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.PayoutRepository;
import com.kmultan.payout.domain.ProcessedMessageRepository;
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
class PayoutParticipantIT {

    @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @ServiceConnection static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");
    static { POSTGRES.start(); KAFKA.start(); }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired FundReservationRepository reservations;
    @Autowired PayoutRepository payouts;
    @Autowired ProcessedMessageRepository processed;
    @Autowired MockMvc mvc;

    Consumer<String, String> replies;
    final List<JsonNode> collected = new ArrayList<>();

    @BeforeEach
    void subscribe() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "it-" + UUID.randomUUID(), "true");
        replies = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        replies.subscribe(List.of("payout.events"));
    }

    @AfterEach
    void close() { replies.close(); }

    private UUID send(UUID claimId, String type, String amount) throws Exception {
        UUID commandId = UUID.randomUUID();
        send(commandId, claimId, type, amount);
        return commandId;
    }

    private void send(UUID commandId, UUID claimId, String type, String amount) throws Exception {
        String body = """
                {"commandId":"%s","type":"%s","claimId":"%s","claimNumber":"CLM-1","policyNumber":"POL-1","amount":%s,"issuedAt":"2026-08-24T10:00:00Z"}
                """.formatted(commandId, type, claimId, amount);
        kafka.send("payout.commands", claimId.toString(), body).get();
    }

    /** Accumulates replies for the claim across calls (a poll consumes records only once). */
    private List<JsonNode> repliesFor(UUID claimId, int expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (ConsumerRecord<String, String> r : replies.poll(Duration.ofMillis(300))) {
                if (r.key().equals(claimId.toString())) collected.add(json.readTree(r.value()));
            }
            assertThat(collected).hasSizeGreaterThanOrEqualTo(expected);
        });
        return collected;
    }

    @Test
    void reserveThenPayoutSettlesTheReservation() throws Exception {
        UUID claim = UUID.randomUUID();
        UUID reserveCmd = send(claim, "RESERVE_FUNDS", "1200.00");
        List<JsonNode> r = repliesFor(claim, 1);
        assertThat(r.get(0).get("type").asText()).isEqualTo("FUNDS_RESERVED");
        assertThat(r.get(0).get("commandId").asText()).isEqualTo(reserveCmd.toString());

        send(claim, "ISSUE_PAYOUT", "1200.00");
        r = repliesFor(claim, 2);
        assertThat(r.get(1).get("type").asText()).isEqualTo("PAYOUT_ISSUED");
        assertThat(r.get(1).get("reference").asText()).startsWith("PAY-");
        assertThat(reservations.findById(claim).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.SETTLED);
        assertThat(payouts.findById(claim).orElseThrow().getStatus()).isEqualTo(Payout.Status.ISSUED);
    }

    @Test
    void duplicateCommandIsProcessedOnce() throws Exception {
        UUID claim = UUID.randomUUID();
        UUID cmd = UUID.randomUUID();
        send(cmd, claim, "RESERVE_FUNDS", "500.00");
        send(cmd, claim, "RESERVE_FUNDS", "500.00");   // redelivery after a crash between commit and offset commit
        send(claim, "ISSUE_PAYOUT", "500.00");

        List<JsonNode> r = repliesFor(claim, 2);
        Thread.sleep(1000);
        for (ConsumerRecord<String, String> rec : replies.poll(Duration.ofMillis(300))) {
            if (rec.key().equals(claim.toString())) collected.add(json.readTree(rec.value()));
        }
        assertThat(r).extracting(n -> n.get("type").asText()).containsExactly("FUNDS_RESERVED", "PAYOUT_ISSUED");
        assertThat(processed.existsById(cmd)).isTrue();
        assertThat(payouts.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void failedPayoutThenReleaseCompensates() throws Exception {
        UUID claim = UUID.randomUUID();
        send(claim, "RESERVE_FUNDS", "300.99");
        send(claim, "ISSUE_PAYOUT", "300.99");
        List<JsonNode> r = repliesFor(claim, 2);
        assertThat(r.get(1).get("type").asText()).isEqualTo("PAYOUT_FAILED");
        assertThat(r.get(1).get("reason").asText()).isEqualTo("Payment provider rejected the transfer");
        assertThat(payouts.findById(claim).orElseThrow().getStatus()).isEqualTo(Payout.Status.FAILED);

        send(claim, "RELEASE_FUNDS", "300.99");
        r = repliesFor(claim, 3);
        assertThat(r.get(2).get("type").asText()).isEqualTo("FUNDS_RELEASED");
        assertThat(reservations.findById(claim).orElseThrow().getStatus()).isEqualTo(FundReservation.Status.RELEASED);
    }

    @Test
    void reservationAboveLimitIsRejected() throws Exception {
        UUID claim = UUID.randomUUID();
        send(claim, "RESERVE_FUNDS", "60000");
        List<JsonNode> r = repliesFor(claim, 1);
        assertThat(r.get(0).get("type").asText()).isEqualTo("RESERVATION_REJECTED");
        assertThat(reservations.findById(claim)).isEmpty();
    }

    @Test
    void poisonMessageGoesToDltAndCanBeReplayed() throws Exception {
        UUID claim = UUID.randomUUID();
        kafka.send("payout.commands", claim.toString(), "{not json").get();
        // 4 attempts with backoff (~3.5s) then DLT
        Thread.sleep(6000);
        mvc.perform(post("/api/v1/dlq/replay")).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(1));
    }
}
