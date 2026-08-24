package com.kmultan.claims.infrastructure.consumers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** claim-service's view of payout-service's events. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PayoutEvent(UUID eventId, String type, UUID claimId, UUID causationEventId, String reference, String reason, Instant occurredAt) {}
