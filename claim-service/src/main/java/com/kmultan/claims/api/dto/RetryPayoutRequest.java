package com.kmultan.claims.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Positive;

/** Optional corrected amount; without it the original approved amount is retried. */
public record RetryPayoutRequest(@Positive BigDecimal approvedAmount) {}
