package com.kmultan.payout.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.payout.domain.FundReservation;
import com.kmultan.payout.domain.FundReservationRepository;
import com.kmultan.payout.domain.Payout;
import com.kmultan.payout.domain.PayoutRepository;

/** The money side of every claim: reservation and transfer state, references, reasons. Finance and admin only. */
@RestController
@RequestMapping("/api/v1/payouts")
@PreAuthorize("hasAnyRole('FINANCE', 'ADMIN')")
public class PayoutLedgerController {

    private final FundReservationRepository reservations;
    private final PayoutRepository payouts;

    public PayoutLedgerController(FundReservationRepository reservations, PayoutRepository payouts) {
        this.reservations = reservations;
        this.payouts = payouts;
    }

    public record LedgerEntry(
            UUID claimId,
            BigDecimal reservedAmount,
            FundReservation.Status reservationStatus,
            BigDecimal payoutAmount,
            Payout.Status payoutStatus,
            String reference,
            String reason,
            Instant updatedAt) {}

    public record LedgerSummary(
            long reservations,
            long payoutsIssued,
            long payoutsFailed,
            long payoutsReversed,
            BigDecimal totalIssued,
            BigDecimal totalReserved,
            List<LedgerEntry> entries) {}

    @GetMapping
    public LedgerSummary ledger() {
        List<FundReservation> allReservations = reservations.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
        Map<UUID, Payout> payoutByClaim =
                payouts.findAll().stream().collect(Collectors.toMap(Payout::getClaimId, Function.identity()));
        List<LedgerEntry> entries = allReservations.stream()
                .map(reservation -> entry(reservation, payoutByClaim.get(reservation.getClaimId())))
                .toList();
        return new LedgerSummary(
                allReservations.size(),
                payoutByClaim.values().stream()
                        .filter(payout -> payout.getStatus() == Payout.Status.ISSUED)
                        .count(),
                payoutByClaim.values().stream()
                        .filter(payout -> payout.getStatus() == Payout.Status.FAILED)
                        .count(),
                payoutByClaim.values().stream()
                        .filter(payout -> payout.getStatus() == Payout.Status.REVERSED)
                        .count(),
                payoutByClaim.values().stream()
                        .filter(payout -> payout.getStatus() == Payout.Status.ISSUED)
                        .map(Payout::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                allReservations.stream()
                        .filter(reservation -> reservation.getStatus() == FundReservation.Status.RESERVED)
                        .map(FundReservation::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                entries);
    }

    @GetMapping("/{claimId}")
    public LedgerEntry forClaim(@PathVariable UUID claimId) {
        FundReservation reservation = reservations
                .findById(claimId)
                .orElseThrow(() -> new IllegalArgumentException("No ledger entry for claim " + claimId));
        return entry(reservation, payouts.findById(claimId).orElse(null));
    }

    private static LedgerEntry entry(FundReservation reservation, Payout payout) {
        return new LedgerEntry(
                reservation.getClaimId(),
                reservation.getAmount(),
                reservation.getStatus(),
                payout == null ? null : payout.getAmount(),
                payout == null ? null : payout.getStatus(),
                payout == null ? null : payout.getReference(),
                payout == null ? null : payout.getReason(),
                payout == null ? reservation.getUpdatedAt() : payout.getUpdatedAt());
    }
}
