package com.kmultan.claims.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ApproveClaimRequest(@NotNull @Positive BigDecimal approvedAmount) {}
