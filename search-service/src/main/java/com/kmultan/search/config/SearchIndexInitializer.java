package com.kmultan.search.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kmultan.search.projection.ClaimDocumentIndexer;
import com.kmultan.search.projection.ClaimEventLogIndexer;

@Configuration
public class SearchIndexInitializer {
    @Bean
    public ApplicationRunner createIndicesOnStartup(
            ClaimDocumentIndexer documentIndexer, ClaimEventLogIndexer eventLogIndexer) {
        return arguments -> {
            documentIndexer.ensureIndex();
            eventLogIndexer.ensureIndex();
        };
    }
}
