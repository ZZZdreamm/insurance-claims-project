package com.kmultan.claims.application.assessment;

import java.math.BigDecimal;

import com.kmultan.claims.domain.Severity;

/** Result of automated damage triage, wherever it came from. */
public record Assessment(
        Severity severity, BigDecimal assessedAmount, String provider, BigDecimal score, String explanation) {
    public Assessment(Severity severity, BigDecimal assessedAmount, String provider) {
        this(severity, assessedAmount, provider, null, null);
    }
}
