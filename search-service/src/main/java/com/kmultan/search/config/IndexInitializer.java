package com.kmultan.search.config;

import com.kmultan.search.projection.ClaimEventLogIndexer;
import com.kmultan.search.projection.ClaimIndexer;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IndexInitializer {
    @Bean
    ApplicationRunner createIndexOnStartup(ClaimIndexer indexer, ClaimEventLogIndexer eventLog) {
        return args -> { indexer.ensureIndex(); eventLog.ensureIndex(); };
    }
}
