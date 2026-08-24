package com.kmultan.claims.domain.event;

/**
 * Port for publishing domain events. The implementation must guarantee the
 * event is durably recorded in the same transaction as the aggregate change —
 * see the outbox adapter. The domain must not know Kafka exists.
 */
public interface DomainEventPublisher {
    void publish(ClaimEvent event);
}
