package com.kmultan.claims.infrastructure.assessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.application.assessment.AssessmentProvider;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.Severity;

/**
 * Deterministic, dependency-free triage: keyword scan of the description plus
 * the policyholder's own estimate. Used only as the timeout fallback when
 * assessment-service does not answer; the result is labelled so the
 * degradation is visible on the claim.
 */
@Component
public class HeuristicAssessmentProvider implements AssessmentProvider {

    private static final List<String> SEVERE =
            List.of("total loss", "fire", "flood", "rolled", "airbag", "frame", "engine");
    private static final List<String> MODERATE =
            List.of("door", "bonnet", "hood", "windscreen", "windshield", "axle", "wheel", "headlight");

    private static final BigDecimal SEVERE_THRESHOLD = new BigDecimal("10000");
    private static final BigDecimal MODERATE_THRESHOLD = new BigDecimal("2500");

    @Override
    public Assessment assess(Claim claim) {
        String text = claim.getDescription().toLowerCase(Locale.ROOT);
        BigDecimal estimate = claim.getEstimatedAmount() == null ? BigDecimal.ZERO : claim.getEstimatedAmount();

        Severity severity;
        if (SEVERE.stream().anyMatch(text::contains) || estimate.compareTo(SEVERE_THRESHOLD) >= 0) {
            severity = Severity.SEVERE;
        } else if (MODERATE.stream().anyMatch(text::contains) || estimate.compareTo(MODERATE_THRESHOLD) >= 0) {
            severity = Severity.MODERATE;
        } else {
            severity = Severity.MINOR;
        }

        // sanity-adjust the estimate towards a per-severity band; an unspecified estimate gets the band floor
        BigDecimal floor =
                switch (severity) {
                    case MINOR -> new BigDecimal("300");
                    case MODERATE -> new BigDecimal("1500");
                    case SEVERE -> new BigDecimal("8000");
                };
        BigDecimal assessed = estimate.max(floor).setScale(2, RoundingMode.HALF_UP);
        return new Assessment(severity, assessed, "heuristic-fallback");
    }
}
