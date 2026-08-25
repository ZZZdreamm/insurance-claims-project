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
    public static ClaimDocument from(ClaimEventEnvelope event) {
        ClaimEventEnvelope.Snapshot snapshot = event.claim();
        return new ClaimDocument(event.claimId(), snapshot.claimNumber(), snapshot.policyNumber(), snapshot.plateNumber(), snapshot.incidentDate(),
                snapshot.description(), snapshot.estimatedAmount(), snapshot.approvedAmount(), snapshot.status(), snapshot.rejectionReason(),
                event.occurredAt(), event.eventType());
    }
}
