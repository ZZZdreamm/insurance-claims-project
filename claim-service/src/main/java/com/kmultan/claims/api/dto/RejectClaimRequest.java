package com.kmultan.claims.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectClaimRequest(@NotBlank @Size(max = 1000) String reason) {}
