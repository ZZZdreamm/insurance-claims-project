package com.kmultan.claims.application.assessment;

import com.kmultan.claims.domain.Severity;

import java.math.BigDecimal;

/** Result of automated damage triage, wherever it came from. */
public record Assessment(Severity severity, BigDecimal assessedAmount, String provider) {}
