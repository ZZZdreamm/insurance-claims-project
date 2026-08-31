package com.kmultan.claims.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.ClaimNumberGenerator;
import com.kmultan.claims.domain.ClaimPayment;
import com.kmultan.claims.domain.ClaimPaymentRepository;
import com.kmultan.claims.domain.ClaimPhoto;
import com.kmultan.claims.domain.ClaimPhotoRepository;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimReserve;
import com.kmultan.claims.domain.ClaimReserveRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Policy;
import com.kmultan.claims.domain.PolicyRepository;
import com.kmultan.claims.domain.PolicyValidationException;
import com.kmultan.claims.domain.Severity;
import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.ClaimEventType;
import com.kmultan.claims.domain.event.DomainEventPublisher;

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
    private final ClaimPhotoRepository claimPhotos;
    private final ClaimNumberGenerator claimNumbers;
    private final PolicyRepository policies;
    private final ClaimReserveRepository reserves;
    private final ClaimPaymentRepository payments;
    private final FraudScreeningService fraudScreening;
    private final CustomerCommunicationService customerCommunications;
    private final DomainEventPublisher events;
    private final ClaimMetrics metrics;
    private final Duration reviewSla;
    private final BigDecimal approvalLimit;

    public ClaimService(
            ClaimRepository claims,
            ClaimPhotoRepository claimPhotos,
            ClaimNumberGenerator claimNumbers,
            PolicyRepository policies,
            ClaimReserveRepository reserves,
            ClaimPaymentRepository payments,
            FraudScreeningService fraudScreening,
            CustomerCommunicationService customerCommunications,
            DomainEventPublisher events,
            ClaimMetrics metrics,
            @Value("${claims.review.sla}") Duration reviewSla,
            @Value("${claims.review.approval-limit:10000}") BigDecimal approvalLimit) {
        this.claims = claims;
        this.claimPhotos = claimPhotos;
        this.claimNumbers = claimNumbers;
        this.policies = policies;
        this.reserves = reserves;
        this.payments = payments;
        this.fraudScreening = fraudScreening;
        this.customerCommunications = customerCommunications;
        this.events = events;
        this.metrics = metrics;
        this.reviewSla = reviewSla;
        this.approvalLimit = approvalLimit;
    }

    public record Photo(String contentType, byte[] data) {}

    private List<UUID> photoIds(UUID claimId) {
        return claimPhotos.findByClaimIdOrderByCreatedAt(claimId).stream()
                .map(ClaimPhoto::getId)
                .toList();
    }

    private void publish(ClaimEventType type, Claim claim) {
        events.publish(ClaimEvent.of(type, claim, photoIds(claim.getId())));
        metrics.transitioned(claim.getStatus());
    }

    // ---- intake ----

    public Claim submit(
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount,
            List<Photo> uploadedPhotos) {
        return submit(policyNumber, plateNumber, incidentDate, description, estimatedAmount, uploadedPhotos, null);
    }

    public Claim submit(
            String policyNumber,
            String plateNumber,
            LocalDate incidentDate,
            String description,
            BigDecimal estimatedAmount,
            List<Photo> uploadedPhotos,
            UUID ownerId) {
        Policy policy = requireCoveringPolicy(policyNumber, incidentDate, ownerId);
        Claim claim = Claim.submit(
                claimNumbers.next(), policyNumber, plateNumber, incidentDate, description, estimatedAmount, ownerId);
        // save() merges (assigned id + primitive version): mutate the managed copy it returns, not the original
        claim = claims.save(claim);
        List<ClaimPhoto> storedPhotos = new java.util.ArrayList<>();
        for (Photo uploadedPhoto : uploadedPhotos) {
            storedPhotos.add(
                    claimPhotos.save(new ClaimPhoto(claim.getId(), uploadedPhoto.contentType(), uploadedPhoto.data())));
        }
        claim.flagForFraudInvestigation(fraudScreening.screen(claim, policy, storedPhotos));
        reserves.save(
                ClaimReserve.open(claim.getId(), estimatedAmount != null ? estimatedAmount : DEFAULT_INITIAL_RESERVE));
        customerCommunications.claimReceived(claim);
        publish(ClaimEventType.CLAIM_SUBMITTED, claim); // assessment-service reacts to this
        metrics.submitted();
        return claim;
    }

    /** No reserve can be derived from a claim without an estimate until the assessment arrives. */
    private static final BigDecimal DEFAULT_INITIAL_RESERVE = new BigDecimal("1500.00");

    private Policy requireCoveringPolicy(String policyNumber, LocalDate incidentDate, UUID ownerId) {
        Policy policy = policies.findById(policyNumber)
                .orElseThrow(() -> new PolicyValidationException("Policy " + policyNumber + " does not exist"));
        if (!policy.coversIncidentOn(incidentDate)) {
            throw new PolicyValidationException("Policy " + policyNumber + " does not cover incidents on "
                    + incidentDate + " (valid " + policy.getValidFrom() + " to " + policy.getValidTo() + ")");
        }
        if (ownerId != null && !policy.acceptsClaimsFrom(ownerId)) {
            throw new PolicyValidationException("Policy " + policyNumber + " belongs to a different policyholder");
        }
        return policy;
    }

    @Transactional(readOnly = true)
    public List<Policy> policiesOf(UUID holderAccountId) {
        return policies.findByHolderAccountIdOrderByPolicyNumber(holderAccountId);
    }

    @Transactional(readOnly = true)
    public List<Policy> allPolicies() {
        return policies.findAll();
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
        return status == null
                ? claims.findByOwnerId(ownerId, pageable)
                : claims.findByOwnerIdAndStatus(ownerId, status, pageable);
    }

    @Transactional(readOnly = true)
    public List<ClaimPhoto> photosOf(UUID claimId) {
        get(claimId);
        return claimPhotos.findByClaimIdOrderByCreatedAt(claimId);
    }

    @Transactional(readOnly = true)
    public ClaimPhoto photo(UUID claimId, UUID photoId) {
        return claimPhotos.findByIdAndClaimId(photoId, claimId).orElseThrow(() -> new ClaimNotFoundException(photoId));
    }

    // ---- triage ----

    /** Reaction to ASSESSMENT_COMPLETED (or the fallback). Ignored if the claim already moved on. */
    public Claim completeAssessment(UUID id, Assessment assessment) {
        Claim claim = get(id);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            log.info("Assessment for claim {} ignored: status is {}", id, claim.getStatus());
            return claim;
        }
        claim.completeAssessment(
                assessment.severity(),
                assessment.assessedAmount(),
                assessment.provider(),
                Instant.now().plus(reviewSla),
                assessment.score(),
                assessment.explanation());
        reserves.findByClaimId(id).ifPresent(reserve -> reserve.adjustTo(reserveAfterAssessment(assessment, claim)));
        customerCommunications.assessmentCompleted(claim);
        publish(ClaimEventType.ASSESSMENT_COMPLETED, claim);
        return claim;
    }

    private static BigDecimal reserveAfterAssessment(Assessment assessment, Claim claim) {
        if (assessment.assessedAmount() != null) {
            return assessment.assessedAmount();
        }
        return switch (claim.getSeverity()) {
            case MINOR -> new BigDecimal("1000.00");
            case MODERATE -> new BigDecimal("5000.00");
            case SEVERE -> new BigDecimal("20000.00");
        };
    }

    // ---- human review ----

    @Transactional(readOnly = true)
    public List<Claim> openReviews() {
        return claims.findByStatusOrderByReviewDueAtAsc(ClaimStatus.PENDING_REVIEW);
    }

    public record ReviewQueueFilter(
            String assignee, boolean unassignedOnly, Severity severity, boolean escalatedOnly, boolean fraudOnly) {}

    public record ReviewQueueSummary(
            long open,
            long unassigned,
            long mine,
            long escalated,
            long severe,
            long fraudSuspected,
            long awaitingSecondApproval) {}

    @Transactional(readOnly = true)
    public Page<Claim> reviewQueue(ReviewQueueFilter filter, Pageable pageable) {
        return claims.findReviewQueue(
                filter.assignee(),
                filter.unassignedOnly(),
                filter.severity(),
                filter.escalatedOnly(),
                filter.fraudOnly(),
                pageable);
    }

    @Transactional(readOnly = true)
    public Page<Claim> awaitingSecondApproval(Pageable pageable) {
        return claims.findByStatus(ClaimStatus.PENDING_SECOND_APPROVAL, pageable);
    }

    @Transactional(readOnly = true)
    public ReviewQueueSummary reviewQueueSummary(String username) {
        return new ReviewQueueSummary(
                claims.countByStatus(ClaimStatus.PENDING_REVIEW),
                claims.countByStatusAndReviewAssigneeIsNull(ClaimStatus.PENDING_REVIEW),
                claims.countByStatusAndReviewAssignee(ClaimStatus.PENDING_REVIEW, username),
                claims.countByStatusAndEscalatedAtIsNotNull(ClaimStatus.PENDING_REVIEW),
                claims.countByStatusAndSeverity(ClaimStatus.PENDING_REVIEW, Severity.SEVERE),
                claims.countByStatusAndFraudFlagsIsNotNull(ClaimStatus.PENDING_REVIEW),
                claims.countByStatus(ClaimStatus.PENDING_SECOND_APPROVAL));
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

    /**
     * The award is capped by the policy's sum insured and reduced by the deductible; an optional
     * advance pays out only a percentage now. Above the approval limit the claim parks for a second,
     * different approver (four-eyes) instead of going straight to payout.
     */
    public Claim approve(UUID id, BigDecimal grossAmount, Integer advancePercent, String approver) {
        if (grossAmount == null || grossAmount.signum() <= 0) {
            throw new IllegalArgumentException("Approved amount must be positive");
        }
        Claim claim = get(id);
        Claim.Settlement settlement = settle(claim, grossAmount);
        BigDecimal firstCycle = advancePercent == null
                ? settlement.payableAmount()
                : settlement
                        .payableAmount()
                        .multiply(BigDecimal.valueOf(advancePercent))
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        reserves.findByClaimId(id).ifPresent(reserve -> reserve.adjustTo(settlement.payableAmount()));
        if (settlement.payableAmount().compareTo(approvalLimit) > 0) {
            claim.parkForSecondApproval(settlement, firstCycle, approver);
            customerCommunications.awaitingSecondApproval(claim);
            publish(ClaimEventType.SECOND_APPROVAL_REQUESTED, claim);
            return claim;
        }
        claim.approve(settlement, firstCycle);
        customerCommunications.decisionApproved(claim);
        publish(ClaimEventType.CLAIM_APPROVED, claim); // payout-service reacts to this
        return claim;
    }

    private Claim.Settlement settle(Claim claim, BigDecimal grossAmount) {
        Policy policy = policies.findById(claim.getPolicyNumber()).orElse(null);
        BigDecimal capped = policy == null ? grossAmount : grossAmount.min(policy.getSumInsured());
        BigDecimal deductible = policy == null ? BigDecimal.ZERO : policy.getDeductible();
        BigDecimal payable = capped.subtract(deductible);
        if (payable.signum() <= 0) {
            throw new PolicyValidationException("Nothing payable: the award of " + grossAmount
                    + " does not exceed the policy deductible of " + deductible);
        }
        return new Claim.Settlement(grossAmount, payable, deductible);
    }

    /** Four-eyes: a different approver confirms what the first one parked. */
    public Claim secondApprove(UUID id, String approver) {
        Claim claim = get(id);
        claim.secondApprove(approver);
        customerCommunications.decisionApproved(claim);
        publish(ClaimEventType.CLAIM_APPROVED, claim);
        return claim;
    }

    /** After an advance, finance releases the remaining payable amount. */
    public Claim payRemainder(UUID id) {
        Claim claim = get(id);
        claim.payRemainder();
        publish(ClaimEventType.CLAIM_APPROVED, claim);
        return claim;
    }

    @Transactional(readOnly = true)
    public List<com.kmultan.claims.domain.CustomerCommunication> communicationsOf(UUID claimId) {
        get(claimId);
        return customerCommunications.historyOf(claimId);
    }

    @Transactional(readOnly = true)
    public Policy policyOf(UUID claimId) {
        return policies.findById(get(claimId).getPolicyNumber()).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ClaimPayment> paymentsOf(UUID claimId) {
        return payments.findByClaimIdOrderByIssuedAt(claimId);
    }

    public record ReserveExposure(String severity, long claims, BigDecimal totalReserved) {}

    public record ReserveSummary(
            long openClaims, BigDecimal totalOpen, BigDecimal totalSettled, List<ReserveExposure> bySeverity) {}

    @Transactional(readOnly = true)
    public ReserveSummary reserveSummary() {
        List<ReserveExposure> bySeverity = reserves.openExposureBySeverity().stream()
                .map(row -> new ReserveExposure(
                        row[0] == null ? "UNASSESSED" : row[0].toString(), (Long) row[1], (BigDecimal) row[2]))
                .toList();
        return new ReserveSummary(
                reserves.countByStatus(ClaimReserve.Status.OPEN),
                reserves.totalByStatus(ClaimReserve.Status.OPEN),
                reserves.totalByStatus(ClaimReserve.Status.SETTLED),
                bySeverity);
    }

    public Claim reject(UUID id, String reason) {
        Claim claim = get(id);
        claim.reject(reason);
        reserves.findByClaimId(id).ifPresent(ClaimReserve::release);
        customerCommunications.decisionRejected(claim);
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
        reserves.findByClaimId(id).ifPresent(ClaimReserve::release);
        publish(ClaimEventType.CLAIM_WITHDRAWN, claim);
        return claim;
    }

    // ---- payout (reactions to payout-service facts) ----

    /** PAYOUT_ISSUED arrived. If the claim can no longer take the money, tell payout-service to reverse it. */
    public Claim acceptPayout(UUID id) {
        return acceptPayout(id, null);
    }

    public Claim acceptPayout(UUID id, String payoutReference) {
        Claim claim = get(id);
        if (claim.getStatus() == ClaimStatus.APPROVED) {
            BigDecimal cycleAmount = claim.recordPayout(payoutReference);
            boolean fullyPaid = claim.getStatus() == ClaimStatus.PAID;
            payments.save(new ClaimPayment(
                    id,
                    cycleAmount,
                    fullyPaid ? ClaimPayment.PaymentType.FINAL : ClaimPayment.PaymentType.ADVANCE,
                    payoutReference));
            reserves.findByClaimId(id).ifPresent(reserve -> {
                if (fullyPaid) {
                    reserve.settle();
                } else {
                    reserve.adjustTo(claim.getPayableAmount().subtract(claim.getPaidAmount()));
                }
            });
            if (fullyPaid) {
                customerCommunications.claimPaid(claim);
            } else {
                customerCommunications.advancePaid(claim);
            }
            publish(fullyPaid ? ClaimEventType.CLAIM_PAID : ClaimEventType.CLAIM_PARTIALLY_PAID, claim);
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
        customerCommunications.payoutFailed(claim);
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
