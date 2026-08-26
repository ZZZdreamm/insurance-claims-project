package com.kmultan.claims.infrastructure.consumers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** claim-service's view of assessment-service's event (own copy; unknown fields ignored). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssessmentEvent(
        UUID eventId,
        String eventType,
        UUID claimId,
        String severity,
        BigDecimal assessedAmount,
        String provider,
        String modelVersion,
        Instant occurredAt,
        BigDecimal score,
        List<String> explanation) {
    public static final String ASSESSMENT_COMPLETED = "ASSESSMENT_COMPLETED";
}
