package com.kmultan.search.projection;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Upserts the claim document using Elasticsearch <em>external</em> versioning
 * with the outbox sequence number. Redelivered or out-of-order events carry a
 * lower-or-equal version and are rejected by ES with a 409, which makes the
 * projection idempotent without any bookkeeping table.
 */
@Component
public class ClaimIndexer {

    private static final Logger log = LoggerFactory.getLogger(ClaimIndexer.class);

    private final ElasticsearchClient es;
    private final String index;

    public ClaimIndexer(ElasticsearchClient es, @Value("${search.index}") String index) {
        this.es = es;
        this.index = index;
    }

    /** @return true if the document was written, false if ES rejected it as stale */
    public boolean index(ClaimDocument doc, long sequence) throws IOException {
        try {
            es.index(IndexRequest.of(r -> r.index(index).id(doc.claimId().toString())
                    .version(sequence).versionType(VersionType.External)
                    .document(doc)));
            return true;
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            return ignoreIfConflict(e.status(), e, doc, sequence);
        } catch (org.elasticsearch.client.ResponseException e) {
            // the transport layer can surface a version conflict before the typed client parses it
            return ignoreIfConflict(e.getResponse().getStatusLine().getStatusCode(), e, doc, sequence);
        }
    }

    private static boolean ignoreIfConflict(int status, Exception e, ClaimDocument doc, long sequence) throws IOException {
        if (status == 409) {
            log.info("Ignoring stale event seq={} for claim {}", sequence, doc.claimId());
            return false;
        }
        if (e instanceof IOException io) {
            throw io;
        }
        throw (RuntimeException) e;
    }

    public void ensureIndex() throws IOException {
        BooleanResponse exists = es.indices().exists(r -> r.index(index));
        if (exists.value()) {
            return;
        }
        es.indices().create(c -> c.index(index).mappings(m -> m
                .properties("claimId", p -> p.keyword(k -> k))
                .properties("claimNumber", p -> p.keyword(k -> k))
                .properties("policyNumber", p -> p.keyword(k -> k))
                .properties("plateNumber", p -> p.text(t -> t.fields("raw", f -> f.keyword(k -> k))))
                .properties("description", p -> p.text(t -> t.analyzer("english")))
                .properties("status", p -> p.keyword(k -> k))
                .properties("rejectionReason", p -> p.text(t -> t))
                .properties("incidentDate", p -> p.date(d -> d))
                .properties("lastEventAt", p -> p.date(d -> d))
                .properties("lastEventType", p -> p.keyword(k -> k))
                .properties("estimatedAmount", p -> p.scaledFloat(s -> s.scalingFactor(100.0)))
                .properties("approvedAmount", p -> p.scaledFloat(s -> s.scalingFactor(100.0)))));
        log.info("Created index {}", index);
    }
}
