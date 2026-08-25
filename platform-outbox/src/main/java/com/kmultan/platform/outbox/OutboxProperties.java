package com.kmultan.platform.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.outbox")
public record OutboxProperties(long pollIntervalMillis, int batchSize) {

    public OutboxProperties {
        if (pollIntervalMillis <= 0) pollIntervalMillis = 1000L;
        if (batchSize <= 0) batchSize = 100;
    }
}
