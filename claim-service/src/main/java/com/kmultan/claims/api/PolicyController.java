package com.kmultan.claims.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Policy;
import com.kmultan.claims.infrastructure.security.AuthenticatedUser;

/** Policyholders see the policies they can claim on; staff see the whole book. */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final ClaimService claimService;

    public PolicyController(ClaimService claimService) {
        this.claimService = claimService;
    }

    public record PolicyResponse(
            String policyNumber,
            String coverageType,
            LocalDate validFrom,
            LocalDate validTo,
            BigDecimal sumInsured,
            BigDecimal deductible,
            boolean active) {
        static PolicyResponse from(Policy policy) {
            return new PolicyResponse(
                    policy.getPolicyNumber(),
                    policy.getCoverageType().name(),
                    policy.getValidFrom(),
                    policy.getValidTo(),
                    policy.getSumInsured(),
                    policy.getDeductible(),
                    policy.coversIncidentOn(LocalDate.now()));
        }
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('POLICYHOLDER', 'ADMIN')")
    public List<PolicyResponse> mine() {
        return claimService.policiesOf(AuthenticatedUser.current().id()).stream()
                .map(PolicyResponse::from)
                .toList();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADJUSTER', 'FINANCE', 'ADMIN')")
    public List<PolicyResponse> all() {
        return claimService.allPolicies().stream().map(PolicyResponse::from).toList();
    }
}
