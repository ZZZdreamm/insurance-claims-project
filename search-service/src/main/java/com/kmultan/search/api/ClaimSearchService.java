package com.kmultan.search.api;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kmultan.search.projection.ClaimDocument;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;

@Service
public class ClaimSearchService {

    private static final List<String> SEARCHED_FIELDS =
            List.of("claimNumber^3", "plateNumber^3", "plateNumber.raw^5", "policyNumber^2", "description");

    private final ElasticsearchClient elasticsearchClient;
    private final String indexName;

    public ClaimSearchService(ElasticsearchClient elasticsearchClient, @Value("${search.index}") String indexName) {
        this.elasticsearchClient = elasticsearchClient;
        this.indexName = indexName;
    }

    public SearchResult search(String queryText, String status, int page, int size) throws IOException {
        Query textQuery = (queryText == null || queryText.isBlank())
                ? Query.of(query -> query.matchAll(matchAll -> matchAll))
                : Query.of(query -> query.multiMatch(multiMatch -> multiMatch
                        .query(queryText)
                        .fields(SEARCHED_FIELDS)
                        .type(TextQueryType.BestFields)
                        .fuzziness("AUTO")));

        Query fullQuery = (status == null || status.isBlank())
                ? textQuery
                : Query.of(query -> query.bool(bool -> bool.must(textQuery)
                        .filter(filter ->
                                filter.term(term -> term.field("status").value(status)))));

        SearchResponse<ClaimDocument> response = elasticsearchClient.search(
                request -> request.index(indexName)
                        .query(fullQuery)
                        .from(page * size)
                        .size(size)
                        .sort(sort -> sort.score(score -> score.order(SortOrder.Desc)))
                        .sort(sort ->
                                sort.field(field -> field.field("lastEventAt").order(SortOrder.Desc))),
                ClaimDocument.class);

        List<ClaimDocument> items =
                response.hits().hits().stream().map(hit -> hit.source()).toList();
        long total = response.hits().total() == null
                ? items.size()
                : response.hits().total().value();
        return new SearchResult(items, total, page, size);
    }

    public record SearchResult(List<ClaimDocument> items, long total, int page, int size) {}
}
