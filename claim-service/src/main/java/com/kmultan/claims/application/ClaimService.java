package com.kmultan.claims.application;

import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.ClaimNumberGenerator;
import com.kmultan.claims.domain.ClaimPhoto;
import com.kmultan.claims.domain.ClaimPhotoRepository;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.ClaimEventType;
import com.kmultan.claims.domain.event.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Use cases. Every state change publishes a fact through the outbox in the
 * same transaction; other services (and this one's scheduler) react to those
 * facts — there is no central orchestrator.
 */
@Service
@Transactional
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private final ClaimRepository claims;
    private final ClaimPhotoRepository photos;
    private final ClaimNumberGenerator claimNumbers;
    private final DomainEventPublisher events;
    private final ClaimMetrics metrics;
    private final Duration reviewSla;

    public ClaimService(ClaimRepository claims, ClaimPhotoRepository photos, ClaimNumberGenerator claimNumbers,
                        DomainEventPublisher events, ClaimMetrics metrics,
                        @Value("${claims.review.sla}") Duration reviewSla) {
        this.claims = claims;
        this.photos = photos;
        this.claimNumbers = claimNumbers;
        this.events = events;
        this.metrics = metrics;
        this.reviewSla = reviewSla;
    }

    public record Photo(String contentType, byte[] data) {}

    private List<UUID> photoIds(UUID claimId) {
        return photos.findByClaimIdOrderByCreatedAt(claimId).stream().map(ClaimPhoto::getId).toList();
    }

    private void publish(ClaimEventType type, Claim claim) {
        events.publish(ClaimEvent.of(type, claim, photoIds(claim.getId())));
        metrics.transitioned(claim.getStatus());
    }

    // ---- intake ----

    public Claim submit(String policyNumber, String plateNumber, LocalDate incidentDate,
                        String description, BigDecimal estimatedAmount, List<Photo> uploadedPhotos) {
        return submit(policyNumber, plateNumber, incidentDate, description, estimatedAmount, uploadedPhotos, null);
    }

    public Claim submit(String policyNumber, String plateNumber, LocalDate incidentDate,
                        String description, BigDecimal estimatedAmount, List<Photo> uploadedPhotos, UUID ownerId) {
        Claim claim = Claim.submit(claimNumbers.next(), policyNumber, plateNumber, incidentDate, description, estimatedAmount, ownerId);
        claims.save(claim);
        for (Photo p : uploadedPhotos) {
            photos.save(new ClaimPhoto(claim.getId(), p.contentType(), p.data()));
        }
        publish(ClaimEventType.CLAIM_SUBMITTED, claim);   // assessment-service reacts to this
        metrics.submitted();
        return claim;
    }

    @Transactional(readOnly = true)
    public Claim get(UUID id) {
        return claims.findById(id).orElseThrow(() -> new ClaimNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Claim> list(ClaimStatus status, Pageable pageable) {
        return status == null ? claims.findAll(pageable) : claims.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Claim> listOwnedBy(UUID ownerId, ClaimStatus status, Pageable pageable) {
        return status == null ? claims.findByOwnerId(ownerId, pageable) : claims.findByOwnerIdAndStatus(ownerId, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<ClaimPhoto> photosOf(UUID claimId) {
        get(claimId);
        return photos.findByClaimIdOrderByCreatedAt(claimId);
    }

    @Transactional(readOnly = true)
    public ClaimPhoto photo(UUID claimId, UUID photoId) {
        return photos.findByIdAndClaimId(photoId, claimId).orElseThrow(() -> new ClaimNotFoundException(photoId));
    }

    // ---- triage ----

    /** Reaction to ASSESSMENT_COMPLETED (or the fallback). Ignored if the claim already moved on. */
    public Claim completeAssessment(UUID id, Assessment assessment) {
        Claim claim = get(id);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            log.info("Assessment for claim {} ignored: status is {}", id, claim.getStatus());
            return claim;
        }
        claim.completeAssessment(assessment.severity(), assessment.assessedAmount(), assessment.provider(), Instant.now().plus(reviewSla));
        publish(ClaimEventType.ASSESSMENT_COMPLETED, claim);
        return claim;
    }

    // ---- human review ----

    @Transactional(readOnly = true)
    public List<Claim> openReviews() {
        return claims.findByStatusOrderByReviewDueAtAsc(ClaimStatus.PENDING_REVIEW);
    }

    public Claim claimReview(UUID id, String assignee) {
        Claim claim = get(id);
        claim.claimReview(assignee);
        publish(ClaimEventType.REVIEW_CLAIMED, claim);
        return claim;
    }

    public Claim unclaimReview(UUID id) {
        Claim claim = get(id);
        claim.unclaimReview();
        publish(ClaimEventType.REVIEW_UNCLAIMED, claim);
        return claim;
    }

    public Claim approve(UUID id, BigDecimal approvedAmount) {
        Claim claim = get(id);
        claim.approve(approvedAmount);
        publish(ClaimEventType.CLAIM_APPROVED, claim);   // payout-service reacts to this
        return claim;
    }

    public Claim reject(UUID id, String reason) {
        Claim claim = get(id);
        claim.reject(reason);
        publish(ClaimEventType.CLAIM_REJECTED, claim);
        return claim;
    }

    /** SLA breach: a fact for notifications/dashboards; the review stays open. */
    public boolean escalateReview(UUID id, Instant now) {
        Claim claim = get(id);
        if (!claim.escalateReview(now)) {
            return false;
        }
        publish(ClaimEventType.REVIEW_SLA_BREACHED, claim);
        return true;
    }

    public Claim withdraw(UUID id) {
        Claim claim = get(id);
        claim.withdraw();
        publish(ClaimEventType.CLAIM_WITHDRAWN, claim);
        return claim;
    }

    // ---- payout (reactions to payout-service facts) ----

    /** PAYOUT_ISSUED arrived. If the claim can no longer take the money, tell payout-service to reverse it. */
    public Claim acceptPayout(UUID id) {
        Claim claim = get(id);
        if (claim.getStatus() == ClaimStatus.APPROVED) {
            claim.markPaid();
            publish(ClaimEventType.CLAIM_PAID, claim);
        } else if (claim.getStatus() == ClaimStatus.PAID) {
            log.info("Payout issued again for already paid claim {} — ignored", id);
        } else {
            log.warn("Payout issued for claim {} in status {} — requesting reversal", id, claim.getStatus());
            events.publish(ClaimEvent.of(ClaimEventType.PAYOUT_UNACCEPTED, claim, photoIds(id)));
        }
        return claim;
    }

    public Claim markPayoutFailed(UUID id, String reason) {
        Claim claim = get(id);
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            log.info("Payout failure for claim {} ignored: status is {}", id, claim.getStatus());
            return claim;
        }
        claim.markPayoutFailed(reason);
        publish(ClaimEventType.PAYOUT_FAILED, claim);
        return claim;
    }

    /** PAYOUT_FAILED -> APPROVED; republishing CLAIM_APPROVED makes payout-service try again. */
    public Claim retryPayout(UUID id, BigDecimal correctedAmount) {
        Claim claim = get(id);
        claim.retryPayout(correctedAmount);
        publish(ClaimEventType.CLAIM_APPROVED, claim);
        return claim;
    }
}
