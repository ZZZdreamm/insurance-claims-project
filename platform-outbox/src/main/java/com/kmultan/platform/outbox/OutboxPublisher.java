package com.kmultan.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
 * Shared by every service that owns an outbox table with this schema.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final OutboxEventRepository outboxEvents;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Counter publishedCounter;
    private final OutboxTraceContext traceContext;

    public OutboxPublisher(
            OutboxEventRepository outboxEvents,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            OutboxTraceContext traceContext,
            OutboxProperties properties) {
        this.outboxEvents = outboxEvents;
        this.kafkaTemplate = kafkaTemplate;
        this.traceContext = traceContext;
        this.properties = properties;
        this.publishedCounter = Counter.builder("outbox.published")
                .description("Outbox events relayed to Kafka")
                .register(meterRegistry);
        meterRegistry.gauge("outbox.pending", outboxEvents, OutboxEventRepository::countByPublishedAtIsNull);
    }

    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval-millis:1000}")
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = outboxEvents.lockUnpublishedBatch(properties.batchSize());
        for (OutboxEvent event : batch) {
            ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                    event.getTopic(), event.getAggregateId().toString(), event.getPayload());
            producerRecord
                    .headers()
                    .add(new RecordHeader(
                            "eventId", event.getEventId().toString().getBytes(StandardCharsets.UTF_8)))
                    .add(new RecordHeader("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8)))
                    .add(new RecordHeader(
                            "sequence", Long.toString(event.getId()).getBytes(StandardCharsets.UTF_8)));
            try {
                // synchronous per record: keeps strict ordering and lets a broker failure roll the batch back;
                // the send runs inside the originating trace, so consumers join the same trace
                traceContext.runInTrace(
                        event.getTraceParent(),
                        "outbox publish " + event.getEventType(),
                        () -> kafkaTemplate.send(producerRecord).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to publish outbox event " + event.getEventId(), exception);
            }
            event.markPublished();
            publishedCounter.increment();
        }
        if (!batch.isEmpty()) {
            log.debug("Published {} outbox event(s)", batch.size());
        }
        return batch.size();
    }
}
