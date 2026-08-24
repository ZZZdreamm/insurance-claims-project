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
    private final ProcessedMessageRepository processed;
    private final PaymentGateway gateway;
    private final PayoutEventPublisher events;
    private final BigDecimal reserveLimit;

    public PayoutSaga(FundReservationRepository reservations, PayoutRepository payouts, ProcessedMessageRepository processed,
                      PaymentGateway gateway, PayoutEventPublisher events,
                      @Value("${payout.reserve-limit}") BigDecimal reserveLimit) {
        this.reservations = reservations;
        this.payouts = payouts;
        this.processed = processed;
        this.gateway = gateway;
        this.events = events;
        this.reserveLimit = reserveLimit;
    }

    private boolean alreadyProcessed(UUID eventId, String type) {
        if (processed.existsById(eventId)) {
            log.info("Duplicate {} {} — skipped", type, eventId);
            return true;
        }
        processed.save(new ProcessedMessage(eventId, type));
        return false;
    }

    /** Step 1: reserve funds for an approved claim. */
    @Transactional
    public void onClaimApproved(ClaimEventEnvelope e) {
        if (alreadyProcessed(e.eventId(), e.eventType())) return;
        BigDecimal amount = e.claim() == null ? null : e.claim().approvedAmount();
        UUID claimId = e.claimId();
        if (amount == null || amount.signum() <= 0) {
            events.publish(PayoutEvent.of(RESERVATION_REJECTED, claimId, e.eventId(), amount, null, "Amount must be positive"));
            return;
        }
        if (amount.compareTo(reserveLimit) > 0) {
            events.publish(PayoutEvent.of(RESERVATION_REJECTED, claimId, e.eventId(), amount, null, "Amount exceeds reserve limit"));
            return;
        }
        FundReservation r = reservations.findById(claimId).orElseGet(() -> new FundReservation(claimId, amount));
        r.reserve(amount, e.eventId());   // re-approval after a failed payout re-reserves on the same row
        reservations.save(r);
        events.publish(PayoutEvent.of(FUNDS_RESERVED, claimId, e.eventId(), amount, null, null));
    }

    /** Step 2: funds are reserved (our own fact) — transfer the money. Compensates step 1 locally on failure. */
    @Transactional
    public void onFundsReserved(PayoutEvent e) {
        if (alreadyProcessed(e.eventId(), e.type().name())) return;
        UUID claimId = e.claimId();
        Optional<FundReservation> reservation = reservations.findById(claimId).filter(r -> r.getStatus() == FundReservation.Status.RESERVED);
        if (reservation.isEmpty()) {
            events.publish(PayoutEvent.of(PAYOUT_FAILED, claimId, e.eventId(), e.amount(), null, "No active reservation for claim"));
            return;
        }
        Payout payout = payouts.findById(claimId).orElseGet(() -> Payout.pending(claimId));
        PaymentGateway.Result result = gateway.transfer(claimId, null, e.amount());
        if (result.success()) {
            payout.issued(e.amount(), result.reference(), e.eventId());
            reservation.get().settle();
            payouts.save(payout);
            events.publish(PayoutEvent.of(PAYOUT_ISSUED, claimId, e.eventId(), e.amount(), result.reference(), null));
        } else {
            payout.failed(e.amount(), result.reason(), e.eventId());
            reservation.get().release();                 // compensation of step 1
            payouts.save(payout);
            events.publish(PayoutEvent.of(PAYOUT_FAILED, claimId, e.eventId(), e.amount(), null, result.reason()));
            events.publish(PayoutEvent.of(FUNDS_RELEASED, claimId, e.eventId(), e.amount(), null, null));
        }
    }

    /** Cross-service compensation: claim-service could not accept the payment (e.g. claim withdrawn meanwhile). */
    @Transactional
    public void onPayoutUnaccepted(ClaimEventEnvelope e) {
        if (alreadyProcessed(e.eventId(), e.eventType())) return;
        UUID claimId = e.claimId();
        payouts.findById(claimId).filter(p -> p.getStatus() == Payout.Status.ISSUED).ifPresent(p -> {
            gateway.reverse(claimId, p.getReference());
            p.reverse();
        });
        reservations.findById(claimId).ifPresent(FundReservation::release);
        events.publish(PayoutEvent.of(PAYOUT_REVERSED, claimId, e.eventId(), null, null, null));
    }
}
