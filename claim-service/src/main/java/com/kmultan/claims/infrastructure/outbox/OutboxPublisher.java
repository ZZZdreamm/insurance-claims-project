package com.kmultan.claims.infrastructure.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and relays events to Kafka.
 *
 * Guarantees: at-least-once delivery, per-aggregate ordering (rows are sent in
 * id order and keyed by aggregate id, so they land on one partition in order).
 * If the broker is down the transaction rolls back and the batch is retried on
 * the next tick; consumers must therefore be idempotent.
 *
 * Debezium reading the WAL would avoid polling entirely; a poller is chosen here
 * because it costs no extra infrastructure and the latency (~1s) is fine for claims.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Counter published;
    private final TraceContextCarrier trace;

    public OutboxPublisher(OutboxRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           MeterRegistry registry,
                           TraceContextCarrier trace,
                           @Value("${claims.outbox.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.trace = trace;
        this.batchSize = batchSize;
        this.published = Counter.builder("outbox.published").description("Outbox events relayed to Kafka").register(registry);
        registry.gauge("outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull);
    }

    @Scheduled(fixedDelayString = "${claims.outbox.poll-interval-ms:1000}")
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = outbox.lockUnpublishedBatch(batchSize);
        for (OutboxEvent event : batch) {
            ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getAggregateId().toString(), event.getPayload());
            record.headers()
                    .add(new RecordHeader("eventId", event.getEventId().toString().getBytes(StandardCharsets.UTF_8)))
                    .add(new RecordHeader("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8)))
                    .add(new RecordHeader("sequence", Long.toString(event.getId()).getBytes(StandardCharsets.UTF_8)));
            try {
                // synchronous per record: keeps strict ordering and lets a broker failure roll the batch back;
                // the send runs inside the originating trace, so consumers join the same trace
                trace.runInTrace(event.getTraceParent(), "outbox publish " + event.getEventType(),
                        () -> kafka.send(record).get(10, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to publish outbox event " + event.getEventId(), e);
            }
            event.markPublished();
            published.increment();
        }
        if (!batch.isEmpty()) {
            log.debug("Published {} outbox event(s)", batch.size());
        }
        return batch.size();
    }
}
