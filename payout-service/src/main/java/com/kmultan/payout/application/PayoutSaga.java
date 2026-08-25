package com.kmultan.payout.application;

import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.PaymentGateway;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;
import com.kmultan.payout.domain.ProcessedMessage;
import com.kmultan.payout.domain.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.kmultan.payout.application.PayoutEvent.Type.*;

/**
 * Choreographed saga participant. No orchestrator tells this service what to
 * do; it reacts to facts:
 * <pre>
 *   CLAIM_APPROVED   (claims.events)  -> reserve funds        -> FUNDS_RESERVED | RESERVATION_REJECTED
 *   FUNDS_RESERVED   (payout.events)  -> issue payout         -> PAYOUT_ISSUED  | PAYOUT_FAILED + FUNDS_RELEASED
 *   PAYOUT_UNACCEPTED(claims.events)  -> reverse + release    -> PAYOUT_REVERSED
 * </pre>
 * Each reaction is ONE local transaction holding the idempotency row, the
 * ledger change and the outbox event, so a redelivered event (consumer killed
 * between commit and offset commit) is a no-op — nothing double-pays.
 * The second step reacts to this service's own event on purpose: every step is
 * then an independently retryable, individually visible fact.
 */
@Service
public class PayoutSaga {

    private static final Logger log = LoggerFactory.getLogger(PayoutSaga.class);

    private final FundReservationRepository reservations;
    private final PayoutRepository payouts;
    private final ProcessedMessageRepository processedMessages;
    private final PaymentGateway paymentGateway;
    private final PayoutEventPublisher eventPublisher;
    private final BigDecimal reserveLimit;

    public PayoutSaga(FundReservationRepository reservations, PayoutRepository payouts, ProcessedMessageRepository processedMessages,
                      PaymentGateway paymentGateway, PayoutEventPublisher eventPublisher,
                      @Value("${payout.reserve-limit}") BigDecimal reserveLimit) {
        this.reservations = reservations;
        this.payouts = payouts;
        this.processedMessages = processedMessages;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.reserveLimit = reserveLimit;
    }

    private boolean alreadyProcessed(UUID eventId, String eventType) {
        if (processedMessages.existsById(eventId)) {
            log.info("Duplicate {} {} — skipped", eventType, eventId);
            return true;
        }
        processedMessages.save(new ProcessedMessage(eventId, eventType));
        return false;
    }

    /** Step 1: reserve funds for an approved claim. */
    @Transactional
    public void onClaimApproved(ClaimEventEnvelope approval) {
        if (alreadyProcessed(approval.eventId(), approval.eventType())) return;
        BigDecimal amount = approval.claim() == null ? null : approval.claim().approvedAmount();
        UUID claimId = approval.claimId();
        if (amount == null || amount.signum() <= 0) {
            eventPublisher.publish(PayoutEvent.of(RESERVATION_REJECTED, claimId, approval.eventId(), amount, null, "Amount must be positive"));
            return;
        }
        if (amount.compareTo(reserveLimit) > 0) {
            eventPublisher.publish(PayoutEvent.of(RESERVATION_REJECTED, claimId, approval.eventId(), amount, null, "Amount exceeds reserve limit"));
            return;
        }
        FundReservation reservation = reservations.findById(claimId).orElseGet(() -> new FundReservation(claimId, amount));
        reservation.reserve(amount, approval.eventId());   // re-approval after a failed payout re-reserves on the same row
        reservations.save(reservation);
        eventPublisher.publish(PayoutEvent.of(FUNDS_RESERVED, claimId, approval.eventId(), amount, null, null));
    }

    /** Step 2: funds are reserved (our own fact) — transfer the money. Compensates step 1 locally on failure. */
    @Transactional
    public void onFundsReserved(PayoutEvent reserved) {
        if (alreadyProcessed(reserved.eventId(), reserved.type().name())) return;
        UUID claimId = reserved.claimId();
        Optional<FundReservation> activeReservation = reservations.findById(claimId)
                .filter(reservation -> reservation.getStatus() == FundReservation.Status.RESERVED);
        if (activeReservation.isEmpty()) {
            eventPublisher.publish(PayoutEvent.of(PAYOUT_FAILED, claimId, reserved.eventId(), reserved.amount(), null, "No active reservation for claim"));
            return;
        }
        Payout payout = payouts.findById(claimId).orElseGet(() -> Payout.pending(claimId));
        PaymentGateway.Result transfer = paymentGateway.transfer(claimId, null, reserved.amount());
        if (transfer.success()) {
            payout.issued(reserved.amount(), transfer.reference(), reserved.eventId());
            activeReservation.get().settle();
            payouts.save(payout);
            eventPublisher.publish(PayoutEvent.of(PAYOUT_ISSUED, claimId, reserved.eventId(), reserved.amount(), transfer.reference(), null));
        } else {
            payout.failed(reserved.amount(), transfer.reason(), reserved.eventId());
            activeReservation.get().release();                 // compensation of step 1
            payouts.save(payout);
            eventPublisher.publish(PayoutEvent.of(PAYOUT_FAILED, claimId, reserved.eventId(), reserved.amount(), null, transfer.reason()));
            eventPublisher.publish(PayoutEvent.of(FUNDS_RELEASED, claimId, reserved.eventId(), reserved.amount(), null, null));
        }
    }

    /** Cross-service compensation: claim-service could not accept the payment (e.g. claim withdrawn meanwhile). */
    @Transactional
    public void onPayoutUnaccepted(ClaimEventEnvelope unaccepted) {
        if (alreadyProcessed(unaccepted.eventId(), unaccepted.eventType())) return;
        UUID claimId = unaccepted.claimId();
        payouts.findById(claimId).filter(payout -> payout.getStatus() == Payout.Status.ISSUED).ifPresent(payout -> {
            paymentGateway.reverse(claimId, payout.getReference());
            payout.reverse();
        });
        reservations.findById(claimId).ifPresent(FundReservation::release);
        eventPublisher.publish(PayoutEvent.of(PAYOUT_REVERSED, claimId, unaccepted.eventId(), null, null, null));
    }
}
