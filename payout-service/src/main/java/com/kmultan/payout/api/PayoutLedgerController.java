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
            List<LedgerEntry> entries,
            int page,
            int totalPages,
            long totalElements) {}

    /**
     * Aggregates come from the database, entries come one page at a time — the ledger grows with
     * every claim and loading it whole would eventually freeze the finance view.
     */
    @GetMapping
    public LedgerSummary ledger(
            @org.springframework.web.bind.annotation.RequestParam(name = "q", required = false) String queryText,
            @org.springframework.data.web.PageableDefault(size = 25)
                    org.springframework.data.domain.Pageable pageable) {
        var sortedPage = org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "updatedAt"));
        org.springframework.data.domain.Page<FundReservation> reservationPage = pageMatching(queryText, sortedPage);
        Map<UUID, Payout> payoutByClaim =
                payouts
                        .findAllById(reservationPage.getContent().stream()
                                .map(FundReservation::getClaimId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Payout::getClaimId, Function.identity()));
        List<LedgerEntry> entries = reservationPage.getContent().stream()
                .map(reservation -> entry(reservation, payoutByClaim.get(reservation.getClaimId())))
                .toList();
        return new LedgerSummary(
                reservationPage.getTotalElements(),
                payouts.countByStatus(Payout.Status.ISSUED),
                payouts.countByStatus(Payout.Status.FAILED),
                payouts.countByStatus(Payout.Status.REVERSED),
                payouts.sumAmountByStatus(Payout.Status.ISSUED),
                reservations.sumAmountByStatus(FundReservation.Status.RESERVED),
                entries,
                reservationPage.getNumber(),
                reservationPage.getTotalPages(),
                reservationPage.getTotalElements());
    }

    /** Matches transfer references (contains) and, when the query parses as a UUID, the claim id too. */
    private org.springframework.data.domain.Page<FundReservation> pageMatching(
            String queryText, org.springframework.data.domain.Pageable pageable) {
        if (queryText == null || queryText.isBlank()) {
            return reservations.findAll(pageable);
        }
        String query = queryText.trim();
        // references from the async gateway are UUID-shaped too, so a UUID query may be either a
        // claim id or a transfer reference — match both and take the union
        java.util.Set<UUID> matching = payouts.findTop100ByReferenceContainingIgnoreCase(query).stream()
                .map(Payout::getClaimId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        try {
            matching.add(UUID.fromString(query));
        } catch (IllegalArgumentException notAClaimId) {
            // plain-text reference search only
        }
        return matching.isEmpty()
                ? org.springframework.data.domain.Page.empty(pageable)
                : reservations.findByClaimIdIn(matching, pageable);
    }

    /** The money view of one claim. */
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
