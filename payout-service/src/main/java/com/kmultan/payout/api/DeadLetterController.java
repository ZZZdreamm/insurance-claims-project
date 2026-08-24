package com.kmultan.payout.api;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Operational endpoint: replay dead-lettered commands back onto the commands
 * topic after the underlying problem has been fixed. Safe to call repeatedly:
 * commands are idempotent, and the replay consumer group commits its offsets.
 */
@RestController
@RequestMapping("/api/v1/dlq")
public class DeadLetterController {

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;
    private final String commandsTopic;

    public DeadLetterController(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka,
                                @Value("${payout.commands-topic}") String commandsTopic) {
        this.consumers = consumers;
        this.kafka = kafka;
        this.commandsTopic = commandsTopic;
    }

    public record ReplayResult(int replayed) {}

    @PostMapping("/replay")
    public ReplayResult replay() throws Exception {
        String dlt = commandsTopic + ".DLT";
        int replayed = 0;
        try (Consumer<String, String> consumer = consumers.createConsumer("payout-dlq-replay", "replay-" + UUID.randomUUID())) {
            consumer.subscribe(List.of(dlt));
            ConsumerRecords<String, String> records;
            int idlePolls = 0;
            while (idlePolls < 3) {
                records = consumer.poll(Duration.ofSeconds(1));
                if (records.isEmpty()) { idlePolls++; continue; }
                for (ConsumerRecord<String, String> r : records) {
                    ProducerRecord<String, String> out = new ProducerRecord<>(commandsTopic, r.key(), r.value());
                    r.headers().forEach(h -> { if (!h.key().startsWith("kafka_")) out.headers().add(h); });
                    kafka.send(out).get();
                    replayed++;
                }
                consumer.commitSync();
            }
        }
        return new ReplayResult(replayed);
    }
}
