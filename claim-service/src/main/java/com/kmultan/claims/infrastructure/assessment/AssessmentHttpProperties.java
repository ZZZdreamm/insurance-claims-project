package com.kmultan.claims.infrastructure.assessment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "claims.assessment.http")
public record AssessmentHttpProperties(String url, Duration timeout) {
    public AssessmentHttpProperties {
        if (timeout == null) timeout = Duration.ofSeconds(3);
    }
}
