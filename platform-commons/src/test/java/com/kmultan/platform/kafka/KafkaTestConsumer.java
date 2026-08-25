package com.kmultan.platform.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A throw-away consumer for assertions: its own group id, subscribed from the
 * earliest offset, accumulating every record it has seen so tests can await
 * "at least N records for this key" without losing what an earlier poll consumed.
 */
public final class KafkaTestConsumer implements AutoCloseable {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(300);

    private final Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> collected = new ArrayList<>();

    public KafkaTestConsumer(String bootstrapServers, String... topics) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(bootstrapServers, "test-" + UUID.randomUUID(), "true");
        this.consumer = new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), new StringDeserializer()).createConsumer();
        this.consumer.subscribe(List.of(topics));
    }

    /** Polls once and returns everything collected so far that matches. */
    public List<ConsumerRecord<String, String>> poll(Predicate<ConsumerRecord<String, String>> filter) {
        consumer.poll(POLL_TIMEOUT).forEach(collected::add);
        return collected.stream().filter(filter).toList();
    }

    public List<ConsumerRecord<String, String>> pollForKey(String key) {
        return poll(consumerRecord -> key.equals(consumerRecord.key()));
    }

    @Override
    public void close() {
        consumer.close();
    }
}
