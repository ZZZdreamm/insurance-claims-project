package com.kmultan.claims.application;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimNotFoundException;
import com.kmultan.claims.domain.ClaimNumberGenerator;
import com.kmultan.claims.application.workflow.ClaimWorkflow;
import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.ClaimEventType;
import com.kmultan.claims.domain.event.DomainEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional
public class ClaimService {

    private final ClaimRepository claims;
    private final ClaimNumberGenerator claimNumbers;
    private final DomainEventPublisher events;
    private final ClaimWorkflow workflow;
    private final ClaimMetrics metrics;

    public ClaimService(ClaimRepository claims, ClaimNumberGenerator claimNumbers,
                        DomainEventPublisher events, ClaimWorkflow workflow, ClaimMetrics metrics) {
        this.claims = claims;
        this.claimNumbers = claimNumbers;
        this.events = events;
        this.workflow = workflow;
        this.metrics = metrics;
    }

    private void publish(ClaimEventType type, Claim claim) {
        events.publish(ClaimEvent.of(type, claim));
        metrics.transitioned(claim.getStatus());
    }

    public Claim submit(String policyNumber, String plateNumber, LocalDate incidentDate,
                        String description, BigDecimal estimatedAmount) {
        Claim claim = Claim.submit(claimNumbers.next(), policyNumber, plateNumber,
                incidentDate, description, estimatedAmount);
        claims.save(claim);
        publish(ClaimEventType.CLAIM_SUBMITTED, claim);
        metrics.submitted();
        workflow.start(claim.getId());   // same transaction: no claim without a process, no process without a claim
        return claim;
    }

    @Transactional(readOnly = true)
    public Claim get(UUID id) {
        return claims.findById(id).orElseThrow(() -> new ClaimNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Claim> list(Pageable pageable) {
        return claims.findAll(pageable);
    }

    public Claim startAssessment(UUID id) {
        Claim claim = get(id);
        claim.startAssessment();
        publish(ClaimEventType.ASSESSMENT_STARTED, claim);
        return claim;
    }

    public Claim completeAssessment(UUID id, BigDecimal assessedAmount) {
        Claim claim = get(id);
        claim.completeAssessment(assessedAmount);
        publish(ClaimEventType.ASSESSMENT_COMPLETED, claim);
        return claim;
    }

    public Claim approve(UUID id, BigDecimal approvedAmount) {
        Claim claim = get(id);
        claim.approve(approvedAmount);
        publish(ClaimEventType.CLAIM_APPROVED, claim);
        return claim;
    }

    public Claim reject(UUID id, String reason) {
        Claim claim = get(id);
        claim.reject(reason);
        publish(ClaimEventType.CLAIM_REJECTED, claim);
        return claim;
    }

    public Claim markPaid(UUID id) {
        Claim claim = get(id);
        claim.markPaid();
        publish(ClaimEventType.CLAIM_PAID, claim);
        return claim;
    }

    public Claim markPayoutFailed(UUID id, String reason) {
        Claim claim = get(id);
        claim.markPayoutFailed(reason);
        publish(ClaimEventType.PAYOUT_FAILED, claim);
        return claim;
    }

    public Claim withdraw(UUID id) {
        Claim claim = get(id);
        claim.withdraw();
        publish(ClaimEventType.CLAIM_WITHDRAWN, claim);
        return claim;
    }
}
