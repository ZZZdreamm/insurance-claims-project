package com.kmultan.platform.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Listener error handling used by every consumer: retry with exponential
 * backoff, then park the record on {@code <topic>.DLT} on the same partition so
 * ordering context survives. A poison message must never block a partition.
 * (No @ConditionalOnBean here: user configurations are evaluated before Boot's
 * KafkaTemplate auto-configuration, so the condition would silently be false.)
 */
@Configuration
public class KafkaDeadLetterConfiguration {

    public static final String DEAD_LETTER_SUFFIX = ".DLT";
    private static final long INITIAL_BACKOFF_MILLIS = 500L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final int MAX_ATTEMPTS = 4;

    @Bean
    public DefaultErrorHandler kafkaListenerErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> failedRecord, Exception exception) ->
                        new TopicPartition(failedRecord.topic() + DEAD_LETTER_SUFFIX, failedRecord.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MILLIS, BACKOFF_MULTIPLIER);
        backOff.setMaxAttempts(MAX_ATTEMPTS);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
