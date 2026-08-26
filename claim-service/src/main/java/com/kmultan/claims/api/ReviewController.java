package com.kmultan.claims.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.claims.api.dto.ApproveClaimRequest;
import com.kmultan.claims.api.dto.ClaimResponse;
import com.kmultan.claims.api.dto.RejectClaimRequest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.infrastructure.security.AuthenticatedUser;

/**
 * Adjuster work queue: claims in PENDING_REVIEW, ordered by SLA due date.
 * The assignee is always the caller — a client cannot claim a review for
 * someone else — and only the holder (or an admin) can decide.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@PreAuthorize("hasAnyRole('ADJUSTER', 'ADMIN')")
public class ReviewController {

    private final ClaimService claimService;
    private final ClaimResponseAssembler responses;

    public ReviewController(ClaimService claimService, ClaimResponseAssembler responses) {
        this.claimService = claimService;
        this.responses = responses;
    }

    @GetMapping
    public List<ClaimResponse> openReviews() {
        return claimService.openReviews().stream().map(responses::toResponse).toList();
    }

    @PostMapping("/{claimId}/claim")
    public ClaimResponse claimReview(@PathVariable UUID claimId) {
        return responses.toResponse(
                claimService.claimReview(claimId, AuthenticatedUser.current().username()));
    }

    @PostMapping("/{claimId}/unclaim")
    public ClaimResponse unclaimReview(@PathVariable UUID claimId) {
        ClaimAccessPolicy.assertHoldsReview(claimService.get(claimId), AuthenticatedUser.current());
        return responses.toResponse(claimService.unclaimReview(claimId));
    }

    @PostMapping("/{claimId}/approve")
    public ClaimResponse approve(@PathVariable UUID claimId, @Valid @RequestBody ApproveClaimRequest request) {
        ClaimAccessPolicy.assertHoldsReview(claimService.get(claimId), AuthenticatedUser.current());
        return responses.toResponse(claimService.approve(claimId, request.approvedAmount()));
    }

    @PostMapping("/{claimId}/reject")
    public ClaimResponse reject(@PathVariable UUID claimId, @Valid @RequestBody RejectClaimRequest request) {
        ClaimAccessPolicy.assertHoldsReview(claimService.get(claimId), AuthenticatedUser.current());
        return responses.toResponse(claimService.reject(claimId, request.reason()));
    }
}
