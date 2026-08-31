package com.kmultan.claims.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.CustomerCommunication;
import com.kmultan.claims.domain.CustomerCommunicationRepository;

/**
 * Composes and records what the policyholder is told at each step. The channel
 * is simulated; the history is the deliverable — an insurer must be able to
 * show every message it sent about a claim.
 */
@Service
public class CustomerCommunicationService {

    private final CustomerCommunicationRepository communications;

    public CustomerCommunicationService(CustomerCommunicationRepository communications) {
        this.communications = communications;
    }

    public List<CustomerCommunication> historyOf(UUID claimId) {
        return communications.findByClaimIdOrderBySentAt(claimId);
    }

    public void claimReceived(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.CLAIM_RECEIVED,
                "We received your claim " + claim.getClaimNumber(),
                "Dear customer,\n\nyour claim " + claim.getClaimNumber() + " for vehicle " + claim.getPlateNumber()
                        + " (policy " + claim.getPolicyNumber() + ", incident on " + claim.getIncidentDate()
                        + ") has been registered. An automated damage assessment starts immediately;"
                        + " an adjuster will review the claim right after.\n\nYour insurer");
    }

    public void assessmentCompleted(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.ASSESSMENT_COMPLETED,
                "Your claim " + claim.getClaimNumber() + " is under review",
                "Dear customer,\n\nthe damage on claim " + claim.getClaimNumber() + " has been classified as "
                        + claim.getSeverity() + " and the claim is now with our claims department."
                        + " We will come back to you with a decision.\n\nYour insurer");
    }

    public void decisionApproved(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.DECISION_APPROVED,
                "Decision on claim " + claim.getClaimNumber() + ": approved",
                "Dear customer,\n\nwe approved your claim " + claim.getClaimNumber() + ".\n\n"
                        + "Awarded amount: " + money(claim.getGrossApprovedAmount()) + "\n"
                        + "Deductible applied: " + money(claim.getDeductibleApplied()) + "\n"
                        + "Amount payable: " + money(claim.getPayableAmount()) + "\n\n"
                        + "The transfer is on its way to your account."
                        + " The formal decision letter is available in the claim view.\n\nYour insurer");
    }

    public void awaitingSecondApproval(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.AWAITING_SECOND_APPROVAL,
                "Your claim " + claim.getClaimNumber() + " needs one more approval",
                "Dear customer,\n\ngood news: your claim " + claim.getClaimNumber()
                        + " has been positively assessed. Because of the amount involved it requires"
                        + " a confirmation by a second claims specialist, which usually takes one business day."
                        + "\n\nYour insurer");
    }

    public void decisionRejected(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.DECISION_REJECTED,
                "Decision on claim " + claim.getClaimNumber() + ": rejected",
                "Dear customer,\n\nafter careful review we cannot accept your claim " + claim.getClaimNumber()
                        + ".\n\nReason: " + claim.getRejectionReason() + "\n\n"
                        + "The formal decision letter with appeal instructions is available in the claim view."
                        + "\n\nYour insurer");
    }

    public void advancePaid(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.ADVANCE_PAID,
                "Advance for claim " + claim.getClaimNumber() + " is on its way",
                "Dear customer,\n\nan advance of " + money(claim.getPaidAmount()) + " for claim "
                        + claim.getClaimNumber() + " has been transferred (reference " + claim.getPayoutReference()
                        + "). The remaining " + money(remaining(claim)) + " follows after final settlement."
                        + "\n\nYour insurer");
    }

    public void claimPaid(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.CLAIM_PAID,
                "Claim " + claim.getClaimNumber() + " paid",
                "Dear customer,\n\nthe payout of " + money(claim.getPaidAmount()) + " for claim "
                        + claim.getClaimNumber() + " has been transferred to your account (reference "
                        + claim.getPayoutReference() + "). Thank you for your patience.\n\nYour insurer");
    }

    public void payoutFailed(Claim claim) {
        record(
                claim,
                CustomerCommunication.Type.PAYOUT_FAILED,
                "A hiccup with the payout for claim " + claim.getClaimNumber(),
                "Dear customer,\n\nthe transfer for claim " + claim.getClaimNumber()
                        + " could not be completed (" + claim.getPayoutFailureReason()
                        + "). Our finance team is on it; no action is needed from you.\n\nYour insurer");
    }

    private void record(Claim claim, CustomerCommunication.Type type, String subject, String body) {
        communications.save(new CustomerCommunication(claim.getId(), type, subject, body));
    }

    private static BigDecimal remaining(Claim claim) {
        return claim.getPayableAmount() == null
                ? BigDecimal.ZERO
                : claim.getPayableAmount().subtract(claim.getPaidAmount());
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "-" : String.format(java.util.Locale.UK, "%,.2f PLN", amount);
    }
}
