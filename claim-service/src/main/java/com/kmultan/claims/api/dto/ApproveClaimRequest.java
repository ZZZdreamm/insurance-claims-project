package com.kmultan.claims.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** advancePercent, when present, pays out only that share now; finance releases the remainder later. */
public record ApproveClaimRequest(
        @NotNull @Positive BigDecimal approvedAmount, @Min(10) @Max(90) Integer advancePercent) {}
