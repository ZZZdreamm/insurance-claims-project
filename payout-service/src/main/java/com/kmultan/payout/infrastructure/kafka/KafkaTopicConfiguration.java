package com.kmultan.payout.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.kmultan.platform.kafka.KafkaDeadLetterConfiguration;

@Configuration
public class KafkaTopicConfiguration {

    private static final int PARTITIONS = 3;

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic payoutEventsTopic(@Value("${payout.events-topic}") String name) {
        return topic(name);
    }

    @Bean
    public NewTopic claimsEventsDeadLetterTopic(@Value("${payout.claims-topic}") String name) {
        return topic(name + KafkaDeadLetterConfiguration.DEAD_LETTER_SUFFIX);
    }

    @Bean
    public NewTopic payoutEventsDeadLetterTopic(@Value("${payout.events-topic}") String name) {
        return topic(name + KafkaDeadLetterConfiguration.DEAD_LETTER_SUFFIX);
    }
}
