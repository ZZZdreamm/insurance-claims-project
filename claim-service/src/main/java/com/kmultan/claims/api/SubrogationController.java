package com.kmultan.claims.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.claims.application.SubrogationService;
import com.kmultan.claims.domain.SubrogationCase;
import com.kmultan.claims.infrastructure.security.AuthenticatedUser;

/** Recovery cases against liable third parties: opened by claims staff, money movements recorded by finance. */
@RestController
@RequestMapping("/api/v1")
public class SubrogationController {

    private final SubrogationService subrogationService;

    public SubrogationController(SubrogationService subrogationService) {
        this.subrogationService = subrogationService;
    }

    public record OpenSubrogationRequest(@NotBlank String liableParty, @NotNull @Positive BigDecimal expectedAmount) {}

    public record RecoveryRequest(@NotNull @Positive BigDecimal amount) {}

    public record WriteOffRequest(@NotBlank String reason) {}

    public record SubrogationResponse(
            UUID id,
            UUID claimId,
            String liableParty,
            BigDecimal expectedAmount,
            BigDecimal recoveredAmount,
            String status,
            String writeOffReason,
            String openedBy,
            Instant openedAt,
            Instant updatedAt) {
        static SubrogationResponse from(SubrogationCase recovery) {
            return new SubrogationResponse(
                    recovery.getId(),
                    recovery.getClaimId(),
                    recovery.getLiableParty(),
                    recovery.getExpectedAmount(),
                    recovery.getRecoveredAmount(),
                    recovery.getStatus().name(),
                    recovery.getWriteOffReason(),
                    recovery.getOpenedBy(),
                    recovery.getOpenedAt(),
                    recovery.getUpdatedAt());
        }
    }

    @PostMapping("/claims/{claimId}/subrogation")
    @PreAuthorize("hasAnyRole('ADJUSTER', 'FINANCE', 'ADMIN')")
    public SubrogationResponse open(@PathVariable UUID claimId, @Valid @RequestBody OpenSubrogationRequest request) {
        return SubrogationResponse.from(subrogationService.open(
                claimId,
                request.liableParty(),
                request.expectedAmount(),
                AuthenticatedUser.current().username()));
    }

    @GetMapping("/claims/{claimId}/subrogation")
    @PreAuthorize("hasAnyRole('ADJUSTER', 'FINANCE', 'ADMIN')")
    public SubrogationResponse forClaim(@PathVariable UUID claimId) {
        return SubrogationResponse.from(subrogationService.forClaim(claimId));
    }

    @PostMapping("/subrogations/{subrogationId}/recoveries")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public SubrogationResponse recordRecovery(
            @PathVariable UUID subrogationId, @Valid @RequestBody RecoveryRequest request) {
        return SubrogationResponse.from(subrogationService.recordRecovery(subrogationId, request.amount()));
    }

    @PostMapping("/subrogations/{subrogationId}/write-off")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public SubrogationResponse writeOff(@PathVariable UUID subrogationId, @Valid @RequestBody WriteOffRequest request) {
        return SubrogationResponse.from(subrogationService.writeOff(subrogationId, request.reason()));
    }

    @GetMapping("/subrogations")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public org.springframework.data.domain.Page<SubrogationResponse> openCases(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.data.web.PageableDefault(size = 25, sort = "openedAt")
                    org.springframework.data.domain.Pageable pageable) {
        return subrogationService.openCases(q, pageable).map(SubrogationResponse::from);
    }

    @GetMapping("/subrogations/summary")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public SubrogationService.RecoverySummary summary() {
        return subrogationService.summary();
    }
}
