package com.kmultan.claims.api;

import org.springframework.stereotype.Component;

import com.kmultan.claims.api.dto.ClaimResponse;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimPhoto;

/** Builds the API representation of a claim, including its photo identifiers. */
@Component
public class ClaimResponseAssembler {

    private final ClaimService claimService;

    public ClaimResponseAssembler(ClaimService claimService) {
        this.claimService = claimService;
    }

    public ClaimResponse toResponse(Claim claim) {
        return ClaimResponse.from(
                claim,
                claimService.photosOf(claim.getId()).stream()
                        .map(ClaimPhoto::getId)
                        .toList());
    }
}
