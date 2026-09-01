package com.kmultan.claims.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.claims.api.dto.ApproveClaimRequest;
import com.kmultan.claims.api.dto.ClaimResponse;
import com.kmultan.claims.api.dto.RejectClaimRequest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Severity;
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

    public enum Scope {
        ALL,
        UNASSIGNED,
        MINE
    }

    /** Paged queue, oldest SLA first. {@code scope=MINE} = held by the caller, {@code UNASSIGNED} = free to take. */
    @GetMapping
    public Page<ClaimResponse> queue(
            @RequestParam(defaultValue = "ALL") Scope scope,
            @RequestParam(required = false) Severity severity,
            @RequestParam(defaultValue = "false") boolean escalatedOnly,
            @RequestParam(defaultValue = "false") boolean fraudOnly,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        String caller = AuthenticatedUser.current().username();
        ClaimService.ReviewQueueFilter filter = new ClaimService.ReviewQueueFilter(
                scope == Scope.MINE ? caller : null, scope == Scope.UNASSIGNED, severity, escalatedOnly, fraudOnly, q);
        return claimService.reviewQueue(filter, pageable).map(responses::toResponse);
    }

    @GetMapping("/summary")
    public ClaimService.ReviewQueueSummary summary() {
        return claimService.reviewQueueSummary(AuthenticatedUser.current().username());
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
        return responses.toResponse(claimService.approve(
                claimId,
                request.approvedAmount(),
                request.advancePercent(),
                AuthenticatedUser.current().username()));
    }

    /** Claims parked above the approval limit, waiting for a second pair of eyes. */
    @GetMapping("/second-approvals")
    public Page<ClaimResponse> awaitingSecondApproval(
            @RequestParam(required = false) String q, @PageableDefault(size = 20) Pageable pageable) {
        return claimService.awaitingSecondApproval(q, pageable).map(responses::toResponse);
    }

    /** Four-eyes: must be a different person than the first approver. */
    @PostMapping("/{claimId}/second-approval")
    public ClaimResponse secondApprove(@PathVariable UUID claimId) {
        return responses.toResponse(
                claimService.secondApprove(claimId, AuthenticatedUser.current().username()));
    }

    @PostMapping("/{claimId}/reject")
    public ClaimResponse reject(@PathVariable UUID claimId, @Valid @RequestBody RejectClaimRequest request) {
        ClaimAccessPolicy.assertHoldsReview(claimService.get(claimId), AuthenticatedUser.current());
        return responses.toResponse(claimService.reject(claimId, request.reason()));
    }
}
