package com.kmultan.claims.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SubmitClaimRequest(
        @NotBlank @Size(max = 32) String policyNumber,
        @NotBlank
                @Size(max = 16)
                @Pattern(regexp = "^[A-Za-z0-9 -]+$", message = "must contain only letters, digits, spaces or dashes")
                String plateNumber,
        @NotNull @PastOrPresent LocalDate incidentDate,
        @NotBlank @Size(min = 10, max = 4000) String description,
        @DecimalMin("0.00") BigDecimal estimatedAmount) {}
