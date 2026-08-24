package com.kmultan.payout.application;

import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.PaymentGateway;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.ProcessedMessage;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.PayoutRepository;
import com.kmultan.payout.domain.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static com.kmultan.payout.application.PayoutEvent.Type.*;

/**
 * Saga participant. Every command is handled in ONE local transaction that
 * contains: the idempotency row, the ledger/payout change, and the outbox reply.
 * If the consumer dies after commit but before the Kafka offset is committed,
 * the redelivered command hits the processed_message primary key and is
 * skipped — so a crash mid-processing can never double-pay.
 */
@Service
public class PayoutCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(PayoutCommandHandler.class);

    private final FundReservationRepository reservations;
    private final PayoutRepository payouts;
    private final ProcessedMessageRepository processed;
    private final PaymentGateway gateway;
    private final PayoutEventPublisher events;
    private final BigDecimal reserveLimit;

    public PayoutCommandHandler(FundReservationRepository reservations, PayoutRepository payouts, ProcessedMessageRepository processed,
                                PaymentGateway gateway, PayoutEventPublisher events,
                                @Value("${payout.reserve-limit}") BigDecimal reserveLimit) {
        this.reservations = reservations;
        this.payouts = payouts;
        this.processed = processed;
        this.gateway = gateway;
        this.events = events;
        this.reserveLimit = reserveLimit;
    }

    /** @return false if the command had already been processed */
    @Transactional
    public boolean handle(PayoutCommand cmd) {
        if (processed.existsById(cmd.commandId())) {
            log.info("Duplicate {} {} for claim {} — skipped", cmd.type(), cmd.commandId(), cmd.claimId());
            return false;
        }
        processed.save(new ProcessedMessage(cmd.commandId(), cmd.type().name()));
        switch (cmd.type()) {
            case RESERVE_FUNDS -> reserve(cmd);
            case ISSUE_PAYOUT -> issue(cmd);
            case RELEASE_FUNDS -> release(cmd);
            case REVERSE_PAYOUT -> reverse(cmd);
        }
        return true;
    }

    private void reserve(PayoutCommand cmd) {
        if (cmd.amount() == null || cmd.amount().signum() <= 0) {
            events.publish(PayoutEvent.reply(RESERVATION_REJECTED, cmd, null, "Amount must be positive"));
            return;
        }
        if (cmd.amount().compareTo(reserveLimit) > 0) {
            events.publish(PayoutEvent.reply(RESERVATION_REJECTED, cmd, null, "Amount exceeds reserve limit"));
            return;
        }
        Optional<FundReservation> existing = reservations.findById(cmd.claimId());
        if (existing.isPresent() && existing.get().getStatus() == FundReservation.Status.RESERVED) {
            events.publish(PayoutEvent.reply(FUNDS_RESERVED, cmd, null, null));   // already reserved: idempotent
            return;
        }
        reservations.save(new FundReservation(cmd.claimId(), cmd.amount()));
        events.publish(PayoutEvent.reply(FUNDS_RESERVED, cmd, null, null));
    }

    private void issue(PayoutCommand cmd) {
        Optional<FundReservation> reservation = reservations.findById(cmd.claimId())
                .filter(r -> r.getStatus() == FundReservation.Status.RESERVED);
        if (reservation.isEmpty()) {
            events.publish(PayoutEvent.reply(PAYOUT_FAILED, cmd, null, "No active reservation for claim"));
            return;
        }
        Optional<Payout> existing = payouts.findById(cmd.claimId());
        if (existing.isPresent() && existing.get().getStatus() == Payout.Status.ISSUED) {
            events.publish(PayoutEvent.reply(PAYOUT_ISSUED, cmd, existing.get().getReference(), null));
            return;
        }
        PaymentGateway.Result result = gateway.transfer(cmd.claimId(), cmd.policyNumber(), cmd.amount());
        if (result.success()) {
            payouts.save(Payout.issued(cmd.claimId(), cmd.amount(), result.reference()));
            reservation.get().settle();
            events.publish(PayoutEvent.reply(PAYOUT_ISSUED, cmd, result.reference(), null));
        } else {
            payouts.save(Payout.failed(cmd.claimId(), cmd.amount(), result.reason()));
            events.publish(PayoutEvent.reply(PAYOUT_FAILED, cmd, null, result.reason()));
        }
    }

    private void release(PayoutCommand cmd) {
        reservations.findById(cmd.claimId())
                .filter(r -> r.getStatus() == FundReservation.Status.RESERVED)
                .ifPresent(FundReservation::release);
        events.publish(PayoutEvent.reply(FUNDS_RELEASED, cmd, null, null));
    }

    private void reverse(PayoutCommand cmd) {
        payouts.findById(cmd.claimId())
                .filter(p -> p.getStatus() == Payout.Status.ISSUED)
                .ifPresent(p -> {
                    gateway.reverse(cmd.claimId(), p.getReference());
                    p.reverse();
                    reservations.findById(cmd.claimId()).ifPresent(FundReservation::release);
                });
        events.publish(PayoutEvent.reply(PAYOUT_REVERSED, cmd, null, null));
    }
}
