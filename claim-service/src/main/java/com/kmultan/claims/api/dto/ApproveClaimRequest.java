package com.kmultan.claims.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ApproveClaimRequest(@NotNull @Positive BigDecimal approvedAmount) {}
