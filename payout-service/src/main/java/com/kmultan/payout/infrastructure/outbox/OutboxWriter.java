package com.kmultan.payout.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/** Single place that appends to the outbox; every message the service emits goes through here. */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final TraceContextCarrier trace;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper mapper, TraceContextCarrier trace) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.trace = trace;
    }

    public void write(String topic, UUID messageId, String aggregateType, UUID aggregateId,
                      String messageType, Object payload, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must happen inside the aggregate's transaction");
        }
        try {
            outbox.save(new OutboxEvent(messageId, topic, aggregateType, aggregateId, messageType,
                    mapper.writeValueAsString(payload), occurredAt).withTraceParent(trace.current()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + messageType, e);
        }
    }
}
