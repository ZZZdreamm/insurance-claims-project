package com.kmultan.claims.infrastructure.payout;

import com.kmultan.claims.application.payout.PayoutCommand;
import com.kmultan.claims.application.payout.PayoutCommandSender;
import com.kmultan.claims.infrastructure.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxPayoutCommandSender implements PayoutCommandSender {

    private final OutboxWriter outbox;
    private final String topic;

    public OutboxPayoutCommandSender(OutboxWriter outbox, @Value("${claims.payout.commands-topic}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Override
    public void send(PayoutCommand command) {
        outbox.write(topic, command.commandId(), "Claim", command.claimId(), command.type().name(), command, command.issuedAt());
    }
}
