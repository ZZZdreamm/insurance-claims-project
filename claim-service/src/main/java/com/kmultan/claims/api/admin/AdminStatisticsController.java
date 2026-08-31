package com.kmultan.claims.api.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.claims.domain.ClaimRepository;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Severity;
import com.kmultan.claims.domain.auth.UserAccountRepository;

/** Business numbers for the admin dashboard, straight from the write model. */
@RestController
@RequestMapping("/api/v1/admin/statistics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatisticsController {

    private static final int DEFAULT_DAYS = 14;

    private final ClaimRepository claims;
    private final UserAccountRepository accounts;
    private final com.kmultan.platform.outbox.OutboxEventRepository outboxEvents;
    private final String claimsTopic;

    /** The event on claims.events that proves a claim reached the status at least once. */
    private static final Map<String, ClaimStatus> STATUS_REACHED_BY_EVENT = Map.of(
            "CLAIM_SUBMITTED", ClaimStatus.SUBMITTED,
            "ASSESSMENT_COMPLETED", ClaimStatus.PENDING_REVIEW,
            "SECOND_APPROVAL_REQUESTED", ClaimStatus.PENDING_SECOND_APPROVAL,
            "CLAIM_APPROVED", ClaimStatus.APPROVED,
            "CLAIM_PARTIALLY_PAID", ClaimStatus.PARTIALLY_PAID,
            "CLAIM_PAID", ClaimStatus.PAID,
            "PAYOUT_FAILED", ClaimStatus.PAYOUT_FAILED,
            "CLAIM_REJECTED", ClaimStatus.REJECTED,
            "CLAIM_WITHDRAWN", ClaimStatus.WITHDRAWN);

    public AdminStatisticsController(
            ClaimRepository claims,
            UserAccountRepository accounts,
            com.kmultan.platform.outbox.OutboxEventRepository outboxEvents,
            @org.springframework.beans.factory.annotation.Value("${claims.topics.claims}") String claimsTopic) {
        this.claims = claims;
        this.accounts = accounts;
        this.outboxEvents = outboxEvents;
        this.claimsTopic = claimsTopic;
    }

    public record Statistics(
            long totalClaims,
            Map<ClaimStatus, Long> byStatus,
            Map<ClaimStatus, Long> everInStatus,
            Map<Severity, Long> bySeverity,
            Map<LocalDate, Long> submittedPerDay,
            long openReviews,
            long escalatedReviews,
            BigDecimal paidTotal,
            BigDecimal approvedAwaitingPayout,
            Double averageSecondsToAssessment,
            Double averageSecondsToPayment,
            long accounts) {}

    @GetMapping
    public Statistics statistics(@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {
        Map<ClaimStatus, Long> byStatus = new EnumMap<>(ClaimStatus.class);
        for (ClaimStatus status : ClaimStatus.values()) {
            byStatus.put(status, 0L);
        }
        claims.countByStatusGrouped().forEach(row -> byStatus.put(row.getStatus(), row.getCount()));

        // lifetime view from the event log: how many claims ever passed through each status,
        // regardless of where they are now — transitional states are otherwise always near zero
        Map<ClaimStatus, Long> everInStatus = new EnumMap<>(ClaimStatus.class);
        for (ClaimStatus status : ClaimStatus.values()) {
            everInStatus.put(status, 0L);
        }
        outboxEvents.countDistinctAggregatesByEventType(claimsTopic).forEach(row -> {
            ClaimStatus reached = STATUS_REACHED_BY_EVENT.get((String) row[0]);
            if (reached != null) {
                everInStatus.merge(reached, (Long) row[1], Long::sum);
            }
        });

        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity, 0L);
        }
        claims.countBySeverityGrouped().forEach(row -> bySeverity.put(row.getSeverity(), row.getCount()));

        LocalDate today = LocalDate.now();
        Map<LocalDate, Long> perDay = new LinkedHashMap<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            perDay.put(today.minusDays(offset), 0L);
        }
        claims.countSubmittedPerDay(Instant.now().minus(days, ChronoUnit.DAYS))
                .forEach(row -> perDay.put(row.getDay(), row.getCount()));

        return new Statistics(
                claims.count(),
                byStatus,
                everInStatus,
                bySeverity,
                perDay,
                claims.countByStatus(ClaimStatus.PENDING_REVIEW),
                claims.countByStatusAndEscalatedAtIsNotNull(ClaimStatus.PENDING_REVIEW),
                claims.sumPaid(),
                claims.sumApprovedAwaitingPayout(),
                claims.averageSecondsToAssessment(),
                claims.averageSecondsFromSubmissionToPayment(),
                accounts.count());
    }
}
