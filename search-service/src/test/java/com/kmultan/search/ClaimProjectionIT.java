package com.kmultan.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.kmultan.search.api.ClaimSearchService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.kmultan.platform.security.TestJwtTokenFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Kafka + real Elasticsearch: publish events, assert they are searchable. */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class ClaimProjectionIT {

    private static final TestJwtTokenFactory TOKENS = new TestJwtTokenFactory();

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static final ElasticsearchContainer ES = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        KAFKA.start();
        ES.start();
    }

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ES.getHttpHostAddress());
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ClaimSearchService claimSearchService;
    @Autowired MockMvc mockMvc;
    @Autowired ElasticsearchClient elasticsearchClient;

    private static String event(UUID claimId, String type, String status, String plate, String description) {
        return """
                {"eventId":"%s","eventType":"%s","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                 "claim":{"claimNumber":"CLM-2026-000001","policyNumber":"POL-77","plateNumber":"%s",
                          "incidentDate":"2026-08-20","description":"%s","estimatedAmount":1200.00,
                          "approvedAmount":null,"status":"%s","rejectionReason":null,"someFutureField":1}}
                """.formatted(UUID.randomUUID(), type, claimId, plate, description, status);
    }

    private void publish(UUID claimId, long sequence, String json) throws Exception {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("claims.events", claimId.toString(), json);
        producerRecord.headers().add(new RecordHeader("sequence", Long.toString(sequence).getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(producerRecord).get();
    }

    @Test
    void projectsEventsAndSupportsFuzzySearch() throws Exception {
        UUID id = UUID.randomUUID();
        publish(id, 10, event(id, "CLAIM_SUBMITTED", "SUBMITTED", "WA12345", "Rear bumper dented in a parking lot"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(claimSearchService.search("bumper", null, 0, 10).items()).anyMatch(document -> document.claimId().equals(id)));

        // fuzzy plate match (one typo)
        assertThat(claimSearchService.search("WA12354", null, 0, 10).items()).anyMatch(document -> document.claimId().equals(id));

        // later event updates status; stale replay of the first is ignored
        publish(id, 11, event(id, "ASSESSMENT_STARTED", "UNDER_ASSESSMENT", "WA12345", "Rear bumper dented in a parking lot"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(claimSearchService.search(null, "UNDER_ASSESSMENT", 0, 10).items()).anyMatch(document -> document.claimId().equals(id)));

        publish(id, 10, event(id, "CLAIM_SUBMITTED", "SUBMITTED", "WA12345", "Rear bumper dented in a parking lot"));
        Thread.sleep(1500);
        assertThat(claimSearchService.search(null, "UNDER_ASSESSMENT", 0, 10).items()).anyMatch(document -> document.claimId().equals(id));

        // the event log keeps every distinct fact (3 event ids were published); the projection kept only the newest state
        elasticsearchClient.indices().refresh(request -> request.index("claim-events"));
        long logged = elasticsearchClient.count(request -> request.index("claim-events").query(query -> query.term(term -> term.field("claimId").value(id.toString())))).count();
        assertThat(logged).isEqualTo(3);

        mockMvc.perform(get("/api/v1/search").param("q", "parking")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/search").param("q", "parking").header("Authorization", TOKENS.bearer("anna", "POLICYHOLDER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/search").param("q", "parking").param("status", "UNDER_ASSESSMENT").header("Authorization", TOKENS.bearer("alice", "ADJUSTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].claimId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].lastEventType").value("ASSESSMENT_STARTED"));
    }
}
