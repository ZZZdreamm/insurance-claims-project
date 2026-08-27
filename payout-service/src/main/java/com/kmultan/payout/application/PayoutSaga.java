package com.kmultan.payout.application;

import static com.kmultan.payout.application.PayoutEvent.Type.FUNDS_RELEASED;
import static com.kmultan.payout.application.PayoutEvent.Type.FUNDS_RESERVED;
import static com.kmultan.payout.application.PayoutEvent.Type.PAYOUT_FAILED;
import static com.kmultan.payout.application.PayoutEvent.Type.PAYOUT_ISSUED;
import static com.kmultan.payout.application.PayoutEvent.Type.PAYOUT_REVERSED;
import static com.kmultan.payout.application.PayoutEvent.Type.RESERVATION_REJECTED;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.PaymentGateway;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;
import com.kmultan.payout.domain.ProcessedMessage;
import com.kmultan.payout.domain.ProcessedMessageRepository;

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

    public PayoutSaga(
            FundReservationRepository reservations,
            PayoutRepository payouts,
            ProcessedMessageRepository processedMessages,
            PaymentGateway paymentGateway,
            PayoutEventPublisher eventPublisher,
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
            eventPublisher.publish(PayoutEvent.of(
                    RESERVATION_REJECTED, claimId, approval.eventId(), amount, null, "Amount must be positive"));
            return;
        }
        if (amount.compareTo(reserveLimit) > 0) {
            eventPublisher.publish(PayoutEvent.of(
                    RESERVATION_REJECTED, claimId, approval.eventId(), amount, null, "Amount exceeds reserve limit"));
            return;
        }
        FundReservation reservation =
                reservations.findById(claimId).orElseGet(() -> new FundReservation(claimId, amount));
        reservation.reserve(
                amount, approval.eventId()); // re-approval after a failed payout re-reserves on the same row
        reservations.save(reservation);
        eventPublisher.publish(PayoutEvent.of(FUNDS_RESERVED, claimId, approval.eventId(), amount, null, null));
    }

    /** Step 2: funds are reserved (our own fact) — transfer the money. Compensates step 1 locally on failure. */
    @Transactional
    public void onFundsReserved(PayoutEvent reserved) {
        if (alreadyProcessed(reserved.eventId(), reserved.type().name())) return;
        UUID claimId = reserved.claimId();
        Optional<FundReservation> activeReservation = reservations
                .findById(claimId)
                .filter(reservation -> reservation.getStatus() == FundReservation.Status.RESERVED);
        if (activeReservation.isEmpty()) {
            eventPublisher.publish(PayoutEvent.of(
                    PAYOUT_FAILED,
                    claimId,
                    reserved.eventId(),
                    reserved.amount(),
                    null,
                    "No active reservation for claim"));
            return;
        }
        Payout payout = payouts.findById(claimId).orElseGet(() -> Payout.pending(claimId));
        PaymentGateway.Result transfer = paymentGateway.transfer(claimId, null, reserved.amount());
        if (transfer.status() == PaymentGateway.TransferStatus.PENDING) {
            // asynchronous provider: hold the reservation, wait for the webhook (or the timeout sweep)
            payout.pendingExternal(reserved.amount(), transfer.reference(), reserved.eventId());
            payouts.save(payout);
            return;
        }
        if (transfer.success()) {
            payout.issued(reserved.amount(), transfer.reference(), reserved.eventId());
            activeReservation.get().settle();
            payouts.save(payout);
            eventPublisher.publish(PayoutEvent.of(
                    PAYOUT_ISSUED, claimId, reserved.eventId(), reserved.amount(), transfer.reference(), null));
        } else {
            payout.failed(reserved.amount(), transfer.reason(), reserved.eventId());
            activeReservation.get().release(); // compensation of step 1
            payouts.save(payout);
            eventPublisher.publish(PayoutEvent.of(
                    PAYOUT_FAILED, claimId, reserved.eventId(), reserved.amount(), null, transfer.reason()));
            eventPublisher.publish(
                    PayoutEvent.of(FUNDS_RELEASED, claimId, reserved.eventId(), reserved.amount(), null, null));
        }
    }

    /** Cross-service compensation: claim-service could not accept the payment (e.g. claim withdrawn meanwhile). */
    @Transactional
    public void onPayoutUnaccepted(ClaimEventEnvelope unaccepted) {
        if (alreadyProcessed(unaccepted.eventId(), unaccepted.eventType())) return;
        UUID claimId = unaccepted.claimId();
        payouts.findById(claimId)
                .filter(payout -> payout.getStatus() == Payout.Status.ISSUED)
                .ifPresent(payout -> {
                    paymentGateway.reverse(claimId, payout.getReference());
                    payout.reverse();
                });
        reservations.findById(claimId).ifPresent(FundReservation::release);
        eventPublisher.publish(PayoutEvent.of(PAYOUT_REVERSED, claimId, unaccepted.eventId(), null, null, null));
    }

    /** Webhook outcome from an asynchronous provider. Idempotent: only a PENDING payout can be completed or rejected. */
    @Transactional
    public boolean onGatewayCallback(String reference, boolean completed, String reason) {
        Optional<Payout> pendingPayout =
                payouts.findByReference(reference).filter(payout -> payout.getStatus() == Payout.Status.PENDING);
        if (pendingPayout.isEmpty()) {
            log.info("Ignoring gateway callback for {} — no pending payout (duplicate or timed out)", reference);
            return false;
        }
        Payout payout = pendingPayout.get();
        UUID claimId = payout.getClaimId();
        UUID causationEventId = payout.getCausationEventId();
        Optional<FundReservation> reservation = reservations.findById(claimId);
        if (completed) {
            payout.issued(payout.getAmount(), reference, causationEventId);
            reservation.ifPresent(FundReservation::settle);
            eventPublisher.publish(
                    PayoutEvent.of(PAYOUT_ISSUED, claimId, causationEventId, payout.getAmount(), reference, null));
        } else {
            payout.failed(payout.getAmount(), reason, causationEventId);
            reservation.ifPresent(FundReservation::release);
            eventPublisher.publish(
                    PayoutEvent.of(PAYOUT_FAILED, claimId, causationEventId, payout.getAmount(), null, reason));
            eventPublisher.publish(
                    PayoutEvent.of(FUNDS_RELEASED, claimId, causationEventId, payout.getAmount(), null, null));
        }
        return true;
    }

    /** A provider that accepted a transfer but never confirmed it: fail and compensate after the timeout. */
    @Transactional
    public int failTimedOutPayouts(Instant olderThan) {
        int failed = 0;
        for (Payout payout : payouts.findByStatusAndUpdatedAtBefore(Payout.Status.PENDING, olderThan)) {
            payout.failed(payout.getAmount(), "Payment provider did not confirm in time", payout.getCausationEventId());
            reservations.findById(payout.getClaimId()).ifPresent(FundReservation::release);
            eventPublisher.publish(PayoutEvent.of(
                    PAYOUT_FAILED,
                    payout.getClaimId(),
                    payout.getCausationEventId(),
                    payout.getAmount(),
                    null,
                    "Payment provider did not confirm in time"));
            eventPublisher.publish(PayoutEvent.of(
                    FUNDS_RELEASED, payout.getClaimId(), payout.getCausationEventId(), payout.getAmount(), null, null));
            failed++;
            log.warn(
                    "Payout {} for claim {} timed out at the provider — compensated",
                    payout.getReference(),
                    payout.getClaimId());
        }
        return failed;
    }
}
