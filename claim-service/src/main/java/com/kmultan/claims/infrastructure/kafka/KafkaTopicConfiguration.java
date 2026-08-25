package com.kmultan.claims.infrastructure.kafka;

import com.kmultan.platform.kafka.KafkaDeadLetterConfiguration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Every topic is partitioned by claim id, so all facts about one claim are consumed in order. */
@Configuration
public class KafkaTopicConfiguration {

    private final int partitions;

    public KafkaTopicConfiguration(@Value("${claims.kafka.partitions:3}") int partitions) {
        this.partitions = partitions;
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }

    @Bean public NewTopic claimsEventsTopic(@Value("${claims.topics.claims}") String name) { return topic(name); }
    @Bean public NewTopic assessmentEventsTopic(@Value("${claims.topics.assessment}") String name) { return topic(name); }
    @Bean public NewTopic payoutEventsTopic(@Value("${claims.topics.payout}") String name) { return topic(name); }
    @Bean public NewTopic assessmentEventsDeadLetterTopic(@Value("${claims.topics.assessment}") String name) { return topic(name + KafkaDeadLetterConfiguration.DEAD_LETTER_SUFFIX); }
    @Bean public NewTopic payoutEventsDeadLetterTopic(@Value("${claims.topics.payout}") String name) { return topic(name + KafkaDeadLetterConfiguration.DEAD_LETTER_SUFFIX); }
}
