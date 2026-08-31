package com.kmultan.claims.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.claims.application.ClaimService;

/** The insurer's open exposure: what the claims on the books are expected to cost. */
@RestController
@RequestMapping("/api/v1/reserves")
public class ReserveController {

    private final ClaimService claimService;

    public ReserveController(ClaimService claimService) {
        this.claimService = claimService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
    public ClaimService.ReserveSummary summary() {
        return claimService.reserveSummary();
    }
}
