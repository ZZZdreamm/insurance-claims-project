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

    private final ElasticsearchClient es;
    private final String index;

    public ClaimEventLogIndexer(ElasticsearchClient es, @Value("${search.event-log-index}") String index) {
        this.es = es;
        this.index = index;
    }

    public void append(ClaimEventEnvelope e, long sequence, JsonNode raw) throws IOException {
        ClaimEventEnvelope.Snapshot s = e.claim();
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("@timestamp", e.occurredAt() == null ? Instant.now() : e.occurredAt());
        doc.put("eventId", e.eventId());
        doc.put("eventType", e.eventType());
        doc.put("sequence", sequence);
        doc.put("claimId", e.claimId());
        if (s != null) {
            doc.put("claimNumber", s.claimNumber());
            doc.put("policyNumber", s.policyNumber());
            doc.put("plateNumber", s.plateNumber());
            doc.put("status", s.status());
            doc.put("estimatedAmount", s.estimatedAmount());
            doc.put("approvedAmount", s.approvedAmount());
            JsonNode sev = raw.path("claim").path("severity");
            if (!sev.isMissingNode() && !sev.isNull()) doc.put("severity", sev.asText());
            JsonNode assignee = raw.path("claim").path("reviewAssignee");
            if (!assignee.isMissingNode() && !assignee.isNull()) doc.put("reviewAssignee", assignee.asText());
            JsonNode escalated = raw.path("claim").path("escalated");
            if (escalated.isBoolean()) doc.put("escalated", escalated.asBoolean());
        }
        es.index(IndexRequest.of(r -> r.index(index).id(e.eventId().toString()).document(doc)));
    }

    public void ensureIndex() throws IOException {
        BooleanResponse exists = es.indices().exists(r -> r.index(index));
        if (exists.value()) return;
        es.indices().create(c -> c.index(index).mappings(m -> m
                .properties("@timestamp", p -> p.date(d -> d))
                .properties("eventId", p -> p.keyword(k -> k))
                .properties("eventType", p -> p.keyword(k -> k))
                .properties("sequence", p -> p.long_(l -> l))
                .properties("claimId", p -> p.keyword(k -> k))
                .properties("claimNumber", p -> p.keyword(k -> k))
                .properties("policyNumber", p -> p.keyword(k -> k))
                .properties("plateNumber", p -> p.keyword(k -> k))
                .properties("status", p -> p.keyword(k -> k))
                .properties("severity", p -> p.keyword(k -> k))
                .properties("reviewAssignee", p -> p.keyword(k -> k))
                .properties("escalated", p -> p.boolean_(b -> b))
                .properties("estimatedAmount", p -> p.scaledFloat(sf -> sf.scalingFactor(100.0)))
                .properties("approvedAmount", p -> p.scaledFloat(sf -> sf.scalingFactor(100.0)))));
        log.info("Created index {}", index);
    }
}
