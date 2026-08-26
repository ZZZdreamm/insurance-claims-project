package com.kmultan.payout.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.platform.kafka.KafkaDeadLetterConfiguration;

/**
 * Operational endpoint: replay dead-lettered events from {@code <topic>.DLT}
 * back onto {@code <topic>} after the underlying problem has been fixed. Safe
 * to call repeatedly: handlers are idempotent, and the replay consumer group
 * commits its offsets.
 */
@RestController
@RequestMapping("/api/v1/dlq")
public class DeadLetterQueueController {

    private static final String REPLAY_GROUP_ID = "payout-dlq-replay";
    private static final int IDLE_POLLS_BEFORE_STOP = 3;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final String INTERNAL_HEADER_PREFIX = "kafka_";

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String claimsTopic;

    public DeadLetterQueueController(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${payout.claims-topic}") String claimsTopic) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.claimsTopic = claimsTopic;
    }

    public record ReplayResult(String topic, int replayed) {}

    /** @param topic source topic whose DLT should be drained (default: the claims events topic) */
    @PostMapping("/replay")
    public ReplayResult replay(@RequestParam(required = false) String topic) throws Exception {
        String sourceTopic = topic == null ? claimsTopic : topic;
        String deadLetterTopic = sourceTopic + KafkaDeadLetterConfiguration.DEAD_LETTER_SUFFIX;
        int replayed = 0;
        try (Consumer<String, String> consumer =
                consumerFactory.createConsumer(REPLAY_GROUP_ID, "replay-" + UUID.randomUUID())) {
            consumer.subscribe(List.of(deadLetterTopic));
            int idlePolls = 0;
            while (idlePolls < IDLE_POLLS_BEFORE_STOP) {
                ConsumerRecords<String, String> deadLetters = consumer.poll(POLL_TIMEOUT);
                if (deadLetters.isEmpty()) {
                    idlePolls++;
                    continue;
                }
                for (ConsumerRecord<String, String> deadLetter : deadLetters) {
                    ProducerRecord<String, String> replayRecord =
                            new ProducerRecord<>(sourceTopic, deadLetter.key(), deadLetter.value());
                    deadLetter.headers().forEach(header -> {
                        if (!header.key().startsWith(INTERNAL_HEADER_PREFIX)) {
                            replayRecord.headers().add(header);
                        }
                    });
                    kafkaTemplate.send(replayRecord).get();
                    replayed++;
                }
                consumer.commitSync();
            }
        }
        return new ReplayResult(sourceTopic, replayed);
    }
}
