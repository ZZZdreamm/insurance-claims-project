package com.kmultan.payout.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic payoutEventsTopic(@Value("${payout.events-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic claimsEventsDlt(@Value("${payout.claims-topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic payoutEventsDlt(@Value("${payout.events-topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    /** Transient failures (DB hiccup, gateway timeout) retry with backoff; poison messages go to the DLT. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (ConsumerRecord<?, ?> r, Exception e) -> new TopicPartition(r.topic() + ".DLT", r.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(4);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
