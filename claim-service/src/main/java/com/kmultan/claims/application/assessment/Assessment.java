package com.kmultan.claims.application.assessment;

import java.math.BigDecimal;

/** Result of automated damage triage. */
public record Assessment(Severity severity, BigDecimal assessedAmount, String provider) {
    public enum Severity { MINOR, MODERATE, SEVERE }
}
