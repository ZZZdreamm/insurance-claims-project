package com.kmultan.claims.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Every topic is partitioned by claim id, so all facts about one claim are consumed in order. */
@Configuration
public class KafkaTopicConfig {

    @Bean NewTopic claimsEvents(@Value("${claims.topics.claims}") String t, @Value("${claims.kafka.partitions:3}") int p) { return TopicBuilder.name(t).partitions(p).replicas(1).build(); }
    @Bean NewTopic assessmentEvents(@Value("${claims.topics.assessment}") String t, @Value("${claims.kafka.partitions:3}") int p) { return TopicBuilder.name(t).partitions(p).replicas(1).build(); }
    @Bean NewTopic payoutEvents(@Value("${claims.topics.payout}") String t, @Value("${claims.kafka.partitions:3}") int p) { return TopicBuilder.name(t).partitions(p).replicas(1).build(); }
    @Bean NewTopic assessmentDlt(@Value("${claims.topics.assessment}") String t, @Value("${claims.kafka.partitions:3}") int p) { return TopicBuilder.name(t + ".DLT").partitions(p).replicas(1).build(); }
    @Bean NewTopic payoutDlt(@Value("${claims.topics.payout}") String t, @Value("${claims.kafka.partitions:3}") int p) { return TopicBuilder.name(t + ".DLT").partitions(p).replicas(1).build(); }
}
