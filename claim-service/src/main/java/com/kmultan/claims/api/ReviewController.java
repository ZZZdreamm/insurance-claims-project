package com.kmultan.claims.api;

import com.kmultan.claims.api.ClaimDtos.ApproveRequest;
import com.kmultan.claims.api.ClaimDtos.ClaimResponse;
import com.kmultan.claims.api.ClaimDtos.ClaimReviewRequest;
import com.kmultan.claims.api.ClaimDtos.RejectRequest;
import com.kmultan.claims.application.ClaimService;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimPhoto;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Adjuster work queue: claims in PENDING_REVIEW, ordered by SLA due date. */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ClaimService service;

    public ReviewController(ClaimService service) {
        this.service = service;
    }

    private ClaimResponse response(Claim c) {
        return ClaimResponse.from(c, service.photosOf(c.getId()).stream().map(ClaimPhoto::getId).toList());
    }

    @GetMapping
    public List<ClaimResponse> open() {
        return service.openReviews().stream().map(this::response).toList();
    }

    @PostMapping("/{id}/claim")
    public ClaimResponse claim(@PathVariable UUID id, @Valid @RequestBody ClaimReviewRequest body) {
        return response(service.claimReview(id, body.assignee()));
    }

    @PostMapping("/{id}/unclaim")
    public ClaimResponse unclaim(@PathVariable UUID id) {
        return response(service.unclaimReview(id));
    }

    @PostMapping("/{id}/approve")
    public ClaimResponse approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest body) {
        return response(service.approve(id, body.approvedAmount()));
    }

    @PostMapping("/{id}/reject")
    public ClaimResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest body) {
        return response(service.reject(id, body.reason()));
    }
}
