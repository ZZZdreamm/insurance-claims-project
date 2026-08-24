package com.kmultan.claims.infrastructure.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmultan.claims.application.payout.PayoutCommand;
import com.kmultan.claims.application.payout.PayoutReply;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for payout-service in claim-service ITs, using the same deterministic
 * rules as the real stub gateway: amounts over 50,000 cannot be reserved and
 * amounts ending in .99 fail at the payment provider. Records every command so
 * tests can assert on compensation traffic.
 */
@TestComponent
public class FakePayoutParticipant {

    public final List<PayoutCommand> received = new CopyOnWriteArrayList<>();

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String repliesTopic;

    public FakePayoutParticipant(KafkaTemplate<String, String> kafka, ObjectMapper json,
                                 @Value("${claims.payout.replies-topic}") String repliesTopic) {
        this.kafka = kafka;
        this.json = json;
        this.repliesTopic = repliesTopic;
    }

    @KafkaListener(topics = "${claims.payout.commands-topic}", groupId = "fake-payout-#{T(java.util.UUID).randomUUID()}")
    public void on(ConsumerRecord<String, String> record) throws Exception {
        PayoutCommand cmd = json.readValue(record.value(), PayoutCommand.class);
        received.add(cmd);
        PayoutReply.Type type;
        String reason = null;
        switch (cmd.type()) {
            case RESERVE_FUNDS -> {
                boolean ok = cmd.amount().compareTo(new BigDecimal("50000")) <= 0;
                type = ok ? PayoutReply.Type.FUNDS_RESERVED : PayoutReply.Type.RESERVATION_REJECTED;
                if (!ok) reason = "Amount exceeds reserve limit";
            }
            case ISSUE_PAYOUT -> {
                boolean ok = cmd.amount().remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.99")) != 0;
                type = ok ? PayoutReply.Type.PAYOUT_ISSUED : PayoutReply.Type.PAYOUT_FAILED;
                if (!ok) reason = "Payment provider rejected the transfer";
            }
            case RELEASE_FUNDS -> type = PayoutReply.Type.FUNDS_RELEASED;
            case REVERSE_PAYOUT -> type = PayoutReply.Type.PAYOUT_REVERSED;
            default -> throw new IllegalStateException();
        }
        PayoutReply reply = new PayoutReply(UUID.randomUUID(), type, cmd.claimId(), cmd.commandId(),
                type == PayoutReply.Type.PAYOUT_ISSUED ? "PAY-" + cmd.commandId().toString().substring(0, 8) : null,
                reason, Instant.now());
        kafka.send(repliesTopic, cmd.claimId().toString(), json.writeValueAsString(reply)).get();
    }

    public List<PayoutCommand> commandsFor(UUID claimId) {
        return received.stream().filter(c -> c.claimId().equals(claimId)).toList();
    }
}
