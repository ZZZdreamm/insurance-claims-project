package com.kmultan.claims.application.assessment;

import com.kmultan.claims.domain.Claim;

/**
 * Port for damage triage. The default adapter is a deterministic heuristic so
 * the demo and the tests need no model download; a vision-model adapter
 * (assessment-service over HTTP) plugs in behind the same interface.
 */
public interface AssessmentProvider {
    Assessment assess(Claim claim);
}
