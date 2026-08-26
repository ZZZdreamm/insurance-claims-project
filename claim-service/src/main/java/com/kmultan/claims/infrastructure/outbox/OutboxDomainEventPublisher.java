package com.kmultan.claims.infrastructure.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.DomainEventPublisher;
import com.kmultan.platform.outbox.OutboxWriter;

/**
 * Writes the event into the outbox table inside the caller's transaction.
 * If the aggregate write rolls back, so does the event. If it commits, the
 * event is guaranteed to exist and {@link OutboxPublisher} will ship it.
 *
 * This is the answer to the dual-write problem: {@code repository.save()}
 * followed by {@code kafkaTemplate.send()} can lose events (commit then broker
 * down) or invent them (send then commit fails). One local transaction cannot.
 */
@Component
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final OutboxWriter outbox;
    private final String topic;

    public OutboxDomainEventPublisher(OutboxWriter outbox, @Value("${claims.topics.claims}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Override
    public void publish(ClaimEvent event) {
        outbox.write(
                topic,
                event.eventId(),
                "Claim",
                event.claimId(),
                event.eventType().name(),
                event,
                event.occurredAt());
    }
}
