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

    public AdminStatisticsController(ClaimRepository claims, UserAccountRepository accounts) {
        this.claims = claims;
        this.accounts = accounts;
    }

    public record Statistics(
            long totalClaims,
            Map<ClaimStatus, Long> byStatus,
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
