package com.kmultan.search.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.kmultan.search.projection.ClaimDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class ClaimSearchService {

    private final ElasticsearchClient es;
    private final String index;

    public ClaimSearchService(ElasticsearchClient es, @Value("${search.index}") String index) {
        this.es = es;
        this.index = index;
    }

    public SearchResult search(String q, String status, int page, int size) throws IOException {
        Query text = (q == null || q.isBlank())
                ? Query.of(b -> b.matchAll(m -> m))
                : Query.of(b -> b.multiMatch(m -> m.query(q)
                        .fields("claimNumber^3", "plateNumber^3", "plateNumber.raw^5", "policyNumber^2", "description")
                        .type(TextQueryType.BestFields)
                        .fuzziness("AUTO")));

        Query full = (status == null || status.isBlank())
                ? text
                : Query.of(b -> b.bool(bb -> bb.must(text).filter(f -> f.term(t -> t.field("status").value(status)))));

        SearchResponse<ClaimDocument> resp = es.search(s -> s.index(index).query(full)
                        .from(page * size).size(size)
                        .sort(so -> so.score(sc -> sc.order(SortOrder.Desc)))
                        .sort(so -> so.field(f -> f.field("lastEventAt").order(SortOrder.Desc))),
                ClaimDocument.class);

        List<ClaimDocument> hits = resp.hits().hits().stream().map(h -> h.source()).toList();
        long total = resp.hits().total() == null ? hits.size() : resp.hits().total().value();
        return new SearchResult(hits, total, page, size);
    }

    public record SearchResult(List<ClaimDocument> items, long total, int page, int size) {}
}
