package com.kmultan.search.projection;

import java.io.IOException;

import org.elasticsearch.client.ResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;

/**
 * Upserts the claim document using Elasticsearch <em>external</em> versioning
 * with the outbox sequence number. Redelivered or out-of-order events carry a
 * lower-or-equal version and are rejected by ES with a 409, which makes the
 * projection idempotent without any bookkeeping table.
 */
@Component
public class ClaimDocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(ClaimDocumentIndexer.class);
    private static final int HTTP_CONFLICT = 409;

    private final ElasticsearchClient elasticsearchClient;
    private final String indexName;

    public ClaimDocumentIndexer(ElasticsearchClient elasticsearchClient, @Value("${search.index}") String indexName) {
        this.elasticsearchClient = elasticsearchClient;
        this.indexName = indexName;
    }

    /** @return true if the document was written, false if ES rejected it as stale */
    public boolean index(ClaimDocument document, long sequence) throws IOException {
        try {
            elasticsearchClient.index(IndexRequest.of(request -> request.index(indexName)
                    .id(document.claimId().toString())
                    .version(sequence)
                    .versionType(VersionType.External)
                    .document(document)));
            return true;
        } catch (ElasticsearchException exception) {
            return ignoreIfConflict(exception.status(), exception, document, sequence);
        } catch (ResponseException exception) {
            // the transport layer can surface a version conflict before the typed client parses it
            return ignoreIfConflict(
                    exception.getResponse().getStatusLine().getStatusCode(), exception, document, sequence);
        }
    }

    private static boolean ignoreIfConflict(int httpStatus, Exception exception, ClaimDocument document, long sequence)
            throws IOException {
        if (httpStatus == HTTP_CONFLICT) {
            log.info("Ignoring stale event seq={} for claim {}", sequence, document.claimId());
            return false;
        }
        if (exception instanceof IOException ioException) {
            throw ioException;
        }
        throw (RuntimeException) exception;
    }

    public void ensureIndex() throws IOException {
        BooleanResponse exists = elasticsearchClient.indices().exists(request -> request.index(indexName));
        if (exists.value()) {
            return;
        }
        elasticsearchClient.indices().create(request -> request.index(indexName)
                .mappings(mappings -> mappings.properties("claimId", property -> property.keyword(keyword -> keyword))
                        .properties("claimNumber", property -> property.keyword(keyword -> keyword))
                        .properties("policyNumber", property -> property.keyword(keyword -> keyword))
                        .properties(
                                "plateNumber",
                                property -> property.text(
                                        text -> text.fields("raw", field -> field.keyword(keyword -> keyword))))
                        .properties("description", property -> property.text(text -> text.analyzer("english")))
                        .properties("status", property -> property.keyword(keyword -> keyword))
                        .properties("rejectionReason", property -> property.text(text -> text))
                        .properties("incidentDate", property -> property.date(date -> date))
                        .properties("lastEventAt", property -> property.date(date -> date))
                        .properties("lastEventType", property -> property.keyword(keyword -> keyword))
                        .properties(
                                "estimatedAmount",
                                property -> property.scaledFloat(scaled -> scaled.scalingFactor(100.0)))
                        .properties(
                                "approvedAmount",
                                property -> property.scaledFloat(scaled -> scaled.scalingFactor(100.0)))));
        log.info("Created index {}", indexName);
    }
}
