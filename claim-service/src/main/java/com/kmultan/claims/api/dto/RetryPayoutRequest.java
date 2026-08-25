package com.kmultan.claims.api.dto;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Optional corrected amount; without it the original approved amount is retried. */
public record RetryPayoutRequest(@Positive BigDecimal approvedAmount) {}
