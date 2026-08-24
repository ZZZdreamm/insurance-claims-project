package com.kmultan.claims.application.assessment;

import com.kmultan.claims.domain.Claim;

/**
 * Port for in-process triage. Normally triage is done by assessment-service
 * reacting to CLAIM_SUBMITTED over Kafka; this port is the fallback used when
 * no result arrives within the configured timeout, so a down ML service never
 * blocks claims.
 */
public interface AssessmentProvider {
    Assessment assess(Claim claim);
}
