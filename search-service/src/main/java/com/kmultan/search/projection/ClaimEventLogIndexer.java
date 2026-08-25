package com.kmultan.search.projection;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only event log: every claim fact as its own document, so Kibana can
 * answer "what happened to claim X" and "how many approvals per hour" without
 * touching the write model. Document id = event id, so redelivery is a
 * harmless overwrite of an identical document.
 */
@Component
public class ClaimEventLogIndexer {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventLogIndexer.class);

    private final ElasticsearchClient elasticsearchClient;
    private final String indexName;

    public ClaimEventLogIndexer(ElasticsearchClient elasticsearchClient, @Value("${search.event-log-index}") String indexName) {
        this.elasticsearchClient = elasticsearchClient;
        this.indexName = indexName;
    }

    public void append(ClaimEventEnvelope event, long sequence, JsonNode rawEvent) throws IOException {
        ClaimEventEnvelope.Snapshot snapshot = event.claim();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("@timestamp", event.occurredAt() == null ? Instant.now() : event.occurredAt());
        document.put("eventId", event.eventId());
        document.put("eventType", event.eventType());
        document.put("sequence", sequence);
        document.put("claimId", event.claimId());
        if (snapshot != null) {
            document.put("claimNumber", snapshot.claimNumber());
            document.put("policyNumber", snapshot.policyNumber());
            document.put("plateNumber", snapshot.plateNumber());
            document.put("status", snapshot.status());
            document.put("estimatedAmount", snapshot.estimatedAmount());
            document.put("approvedAmount", snapshot.approvedAmount());
            JsonNode severity = rawEvent.path("claim").path("severity");
            if (!severity.isMissingNode() && !severity.isNull()) document.put("severity", severity.asText());
            JsonNode reviewAssignee = rawEvent.path("claim").path("reviewAssignee");
            if (!reviewAssignee.isMissingNode() && !reviewAssignee.isNull()) document.put("reviewAssignee", reviewAssignee.asText());
            JsonNode escalated = rawEvent.path("claim").path("escalated");
            if (escalated.isBoolean()) document.put("escalated", escalated.asBoolean());
        }
        elasticsearchClient.index(IndexRequest.of(request -> request.index(indexName).id(event.eventId().toString()).document(document)));
    }

    public void ensureIndex() throws IOException {
        BooleanResponse exists = elasticsearchClient.indices().exists(request -> request.index(indexName));
        if (exists.value()) return;
        elasticsearchClient.indices().create(request -> request.index(indexName).mappings(mappings -> mappings
                .properties("@timestamp", property -> property.date(date -> date))
                .properties("eventId", property -> property.keyword(keyword -> keyword))
                .properties("eventType", property -> property.keyword(keyword -> keyword))
                .properties("sequence", property -> property.long_(number -> number))
                .properties("claimId", property -> property.keyword(keyword -> keyword))
                .properties("claimNumber", property -> property.keyword(keyword -> keyword))
                .properties("policyNumber", property -> property.keyword(keyword -> keyword))
                .properties("plateNumber", property -> property.keyword(keyword -> keyword))
                .properties("status", property -> property.keyword(keyword -> keyword))
                .properties("severity", property -> property.keyword(keyword -> keyword))
                .properties("reviewAssignee", property -> property.keyword(keyword -> keyword))
                .properties("escalated", property -> property.boolean_(flag -> flag))
                .properties("estimatedAmount", property -> property.scaledFloat(scaled -> scaled.scalingFactor(100.0)))
                .properties("approvedAmount", property -> property.scaledFloat(scaled -> scaled.scalingFactor(100.0)))));
        log.info("Created index {}", indexName);
    }
}
