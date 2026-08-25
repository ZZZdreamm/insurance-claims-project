package com.kmultan.search;

import com.kmultan.search.api.ClaimSearchService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Projection throughput and query latency on a real Elasticsearch. Run with {@code mvn verify -Dperf}. */
@Tag("perf")
@SpringBootTest
class SearchPerformanceIT {

    @ServiceConnection static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");
    static final ElasticsearchContainer ES = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
            .withEnv("xpack.security.enabled", "false").withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
    static { KAFKA.start(); ES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) { r.add("spring.elasticsearch.uris", () -> "http://" + ES.getHttpHostAddress()); }

    static final int N = 300;
    static final String[] WORDS = {"bumper", "windscreen", "door", "bonnet", "headlight", "airbag", "fire", "scratch"};

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ClaimSearchService search;

    @Test
    void projectsThreeHundredEventsAndAnswersFuzzyQueriesFast() throws Exception {
        long start = System.currentTimeMillis();
        for (int i = 0; i < N; i++) {
            UUID id = UUID.randomUUID();
            String json = """
                    {"eventId":"%s","eventType":"CLAIM_SUBMITTED","claimId":"%s","occurredAt":"2026-08-24T10:00:00Z",
                     "claim":{"claimNumber":"CLM-2026-%06d","policyNumber":"POL-%d","plateNumber":"PF%05d","incidentDate":"2026-08-20",
                              "description":"Perf claim %d with damaged %s","estimatedAmount":%d,"status":"SUBMITTED"}}"""
                    .formatted(UUID.randomUUID(), id, i, i % 40, i, i, WORDS[i % WORDS.length], 100 + i);
            ProducerRecord<String, String> rec = new ProducerRecord<>("claims.events", id.toString(), json);
            rec.headers().add(new RecordHeader("sequence", Long.toString(i + 1).getBytes(StandardCharsets.UTF_8)));
            kafka.send(rec);
        }
        kafka.flush();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(search.search(null, null, 0, 1).total()).isGreaterThanOrEqualTo(N));
        long indexMillis = System.currentTimeMillis() - start;
        System.out.printf("PERF %-32s %d events -> searchable in %d ms (%.1f docs/s, includes ES refresh interval)%n", "projection", N, indexMillis, N * 1000.0 / indexMillis);

        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String q = i % 2 == 0 ? WORDS[i % WORDS.length] : "PF" + String.format("%05d", i).replace('0', '9').substring(0, 5);   // fuzzy plate
            long s = System.nanoTime();
            search.search(q, null, 0, 20);
            latencies.add((System.nanoTime() - s) / 1_000_000);
        }
        latencies.sort(Long::compare);
        long p50 = latencies.get(49), p95 = latencies.get(94), p99 = latencies.get(98);
        System.out.printf("PERF %-32s n=100  p50=%dms  p95=%dms  p99=%dms%n", "fuzzy search (ES client)", p50, p95, p99);
        assertThat(p95).isLessThan(250);
    }
}
