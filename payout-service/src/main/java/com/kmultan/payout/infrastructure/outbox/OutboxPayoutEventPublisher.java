package com.kmultan.payout.infrastructure.outbox;

import com.kmultan.payout.application.PayoutEvent;
import com.kmultan.payout.application.PayoutEventPublisher;
import com.kmultan.platform.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxPayoutEventPublisher implements PayoutEventPublisher {
    private final OutboxWriter outboxWriter;
    private final String topic;

    public OutboxPayoutEventPublisher(OutboxWriter outboxWriter, @Value("${payout.events-topic}") String topic) {
        this.outboxWriter = outboxWriter;
        this.topic = topic;
    }

    @Override
    public void publish(PayoutEvent event) {
        outboxWriter.write(topic, event.eventId(), "Payout", event.claimId(), event.type().name(), event, event.occurredAt());
    }
}
