package com.kmultan.search.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

/** What happened to one claim, in order — read from the append-only event log. */
@Service
public class ClaimEventTimelineService {

    private static final int MAX_EVENTS = 500;

    private final ElasticsearchClient elasticsearchClient;
    private final String eventLogIndexName;

    public ClaimEventTimelineService(
            ElasticsearchClient elasticsearchClient, @Value("${search.event-log-index}") String eventLogIndexName) {
        this.elasticsearchClient = elasticsearchClient;
        this.eventLogIndexName = eventLogIndexName;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> timeline(UUID claimId) throws IOException {
        SearchResponse<Map> response = elasticsearchClient.search(
                request -> request.index(eventLogIndexName)
                        .query(query -> query.term(term -> term.field("claimId").value(claimId.toString())))
                        .size(MAX_EVENTS)
                        .sort(sort ->
                                sort.field(field -> field.field("sequence").order(SortOrder.Asc))),
                Map.class);
        return response.hits().hits().stream()
                .map(Hit::source)
                .map(source -> (Map<String, Object>) source)
                .toList();
    }
}
