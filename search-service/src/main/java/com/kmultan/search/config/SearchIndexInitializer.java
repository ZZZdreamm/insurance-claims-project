package com.kmultan.search.config;

import com.kmultan.search.projection.ClaimEventLogIndexer;
import com.kmultan.search.projection.ClaimDocumentIndexer;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SearchIndexInitializer {
    @Bean
    public ApplicationRunner createIndicesOnStartup(ClaimDocumentIndexer documentIndexer, ClaimEventLogIndexer eventLogIndexer) {
        return arguments -> {
            documentIndexer.ensureIndex();
            eventLogIndexer.ensureIndex();
        };
    }
}
