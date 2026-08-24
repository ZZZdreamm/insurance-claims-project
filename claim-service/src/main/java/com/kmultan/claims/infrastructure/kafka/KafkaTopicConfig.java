package com.kmultan.claims.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    /** Partitioned by claim id, so every event for one claim is consumed in order. */
    @Bean
    NewTopic claimsEventsTopic(@Value("${claims.kafka.topic}") String topic,
                               @Value("${claims.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic payoutCommandsTopic(@Value("${claims.payout.commands-topic}") String topic,
                                 @Value("${claims.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic payoutEventsTopic(@Value("${claims.payout.replies-topic}") String topic,
                               @Value("${claims.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
