package com.kmultan.claims.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.SubrogationCase;
import com.kmultan.claims.domain.SubrogationCaseRepository;
import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.ClaimEventType;
import com.kmultan.claims.domain.event.DomainEventPublisher;

/**
 * Recovery of paid claims from the liable third party. A case opens only after
 * money actually left (PAID or an advance), accumulates recoveries and closes
 * as RECOVERED or WRITTEN_OFF; every step lands on the claim's timeline.
 */
@Service
@Transactional
public class SubrogationService {

    private final SubrogationCaseRepository subrogations;
    private final ClaimService claimService;
    private final DomainEventPublisher events;

    public SubrogationService(
            SubrogationCaseRepository subrogations, ClaimService claimService, DomainEventPublisher events) {
        this.subrogations = subrogations;
        this.claimService = claimService;
        this.events = events;
    }

    public SubrogationCase open(UUID claimId, String liableParty, BigDecimal expectedAmount, String openedBy) {
        Claim claim = claimService.get(claimId);
        if (claim.getStatus() != ClaimStatus.PAID && claim.getStatus() != ClaimStatus.PARTIALLY_PAID) {
            throw new IllegalStateException("Subrogation starts after payout; claim is " + claim.getStatus());
        }
        subrogations.findByClaimId(claimId).ifPresent(existing -> {
            throw new IllegalStateException("A subrogation case is already open for claim " + claim.getClaimNumber());
        });
        SubrogationCase recovery =
                subrogations.save(SubrogationCase.open(claimId, liableParty, expectedAmount, openedBy));
        publish(ClaimEventType.SUBROGATION_OPENED, claim);
        return recovery;
    }

    public SubrogationCase recordRecovery(UUID subrogationId, BigDecimal amount) {
        SubrogationCase recovery = get(subrogationId);
        boolean completed = recovery.recordRecovery(amount);
        Claim claim = claimService.get(recovery.getClaimId());
        publish(ClaimEventType.SUBROGATION_RECOVERY_RECORDED, claim);
        if (completed) {
            publish(ClaimEventType.SUBROGATION_CLOSED, claim);
        }
        return recovery;
    }

    public SubrogationCase writeOff(UUID subrogationId, String reason) {
        SubrogationCase recovery = get(subrogationId);
        recovery.writeOff(reason);
        publish(ClaimEventType.SUBROGATION_CLOSED, claimService.get(recovery.getClaimId()));
        return recovery;
    }

    @Transactional(readOnly = true)
    public SubrogationCase get(UUID subrogationId) {
        return subrogations.findById(subrogationId).orElseThrow(() -> new ClaimNotFoundException(subrogationId));
    }

    @Transactional(readOnly = true)
    public SubrogationCase forClaim(UUID claimId) {
        return subrogations.findByClaimId(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<SubrogationCase> openCases(
            String queryText, org.springframework.data.domain.Pageable pageable) {
        if (queryText != null && !queryText.isBlank()) {
            return subrogations.findByStatusAndLiablePartyContainingIgnoreCase(
                    SubrogationCase.Status.OPEN, queryText.trim(), pageable);
        }
        return subrogations.findByStatus(SubrogationCase.Status.OPEN, pageable);
    }

    public record RecoverySummary(long openCases, BigDecimal expectedOpen, BigDecimal totalRecovered) {}

    @Transactional(readOnly = true)
    public RecoverySummary summary() {
        return new RecoverySummary(
                subrogations.countByStatus(SubrogationCase.Status.OPEN),
                subrogations.totalExpectedByStatus(SubrogationCase.Status.OPEN),
                subrogations.totalRecovered());
    }

    private void publish(ClaimEventType type, Claim claim) {
        events.publish(ClaimEvent.of(type, claim, List.of()));
    }
}
