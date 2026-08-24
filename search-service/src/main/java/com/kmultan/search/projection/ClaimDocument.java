package com.kmultan.search.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Denormalised read model stored in Elasticsearch. */
public record ClaimDocument(
        UUID claimId,
        String claimNumber,
        String policyNumber,
        String plateNumber,
        LocalDate incidentDate,
        String description,
        BigDecimal estimatedAmount,
        BigDecimal approvedAmount,
        String status,
        String rejectionReason,
        Instant lastEventAt,
        String lastEventType
) {
    public static ClaimDocument from(ClaimEventEnvelope e) {
        ClaimEventEnvelope.Snapshot s = e.claim();
        return new ClaimDocument(e.claimId(), s.claimNumber(), s.policyNumber(), s.plateNumber(), s.incidentDate(),
                s.description(), s.estimatedAmount(), s.approvedAmount(), s.status(), s.rejectionReason(),
                e.occurredAt(), e.eventType());
    }
}
