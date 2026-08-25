package com.kmultan.search;

import com.kmultan.search.api.ClaimSearchService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.elasticsearch.uris", () -> "http://" + ES.getHttpHostAddress());
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ClaimSearchService search;
    @Autowired MockMvc mvc;
    @Autowired co.elastic.clients.elasticsearch.ElasticsearchClient es;

    private static String event(UUID claimId, String type, String status, String plate, String description) {
        return """
                {"eventId":"%s","eventType":"%s","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                 "claim":{"claimNumber":"CLM-2026-000001","policyNumber":"POL-77","plateNumber":"%s",
                          "incidentDate":"2026-08-20","description":"%s","estimatedAmount":1200.00,
                          "approvedAmount":null,"status":"%s","rejectionReason":null,"someFutureField":1}}
                """.formatted(UUID.randomUUID(), type, claimId, plate, description, status);
    }

    private void publish(UUID claimId, long sequence, String json) throws Exception {
        ProducerRecord<String, String> rec = new ProducerRecord<>("claims.events", claimId.toString(), json);
        rec.headers().add(new RecordHeader("sequence", Long.toString(sequence).getBytes(StandardCharsets.UTF_8)));
        kafka.send(rec).get();
    }

    @Test
    void projectsEventsAndSupportsFuzzySearch() throws Exception {
        UUID id = UUID.randomUUID();
        publish(id, 10, event(id, "CLAIM_SUBMITTED", "SUBMITTED", "WA12345", "Rear bumper dented in a parking lot"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(search.search("bumper", null, 0, 10).items()).anyMatch(d -> d.claimId().equals(id)));

        // fuzzy plate match (one typo)
        assertThat(search.search("WA12354", null, 0, 10).items()).anyMatch(d -> d.claimId().equals(id));

        // later event updates status; stale replay of the first is ignored
        publish(id, 11, event(id, "ASSESSMENT_STARTED", "UNDER_ASSESSMENT", "WA12345", "Rear bumper dented in a parking lot"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(search.search(null, "UNDER_ASSESSMENT", 0, 10).items()).anyMatch(d -> d.claimId().equals(id)));

        publish(id, 10, event(id, "CLAIM_SUBMITTED", "SUBMITTED", "WA12345", "Rear bumper dented in a parking lot"));
        Thread.sleep(1500);
        assertThat(search.search(null, "UNDER_ASSESSMENT", 0, 10).items()).anyMatch(d -> d.claimId().equals(id));

        // the event log keeps every distinct fact (3 event ids were published); the projection kept only the newest state
        es.indices().refresh(r -> r.index("claim-events"));
        long logged = es.count(c -> c.index("claim-events").query(q -> q.term(t -> t.field("claimId").value(id.toString())))).count();
        assertThat(logged).isEqualTo(3);

        mvc.perform(get("/api/v1/search").param("q", "parking")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/search").param("q", "parking").header("Authorization", TestTokens.bearer("anna", "POLICYHOLDER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/search").param("q", "parking").param("status", "UNDER_ASSESSMENT").header("Authorization", TestTokens.bearer("alice", "ADJUSTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].claimId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].lastEventType").value("ASSESSMENT_STARTED"));
    }
}
