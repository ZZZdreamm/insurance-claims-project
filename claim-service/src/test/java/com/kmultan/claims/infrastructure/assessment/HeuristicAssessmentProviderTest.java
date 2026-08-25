package com.kmultan.claims.infrastructure.assessment;

import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.domain.Severity;
import com.kmultan.claims.domain.Claim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicAssessmentProviderTest {

    private final HeuristicAssessmentProvider provider = new HeuristicAssessmentProvider();

    @ParameterizedTest
    @CsvSource({
            "'Scratched rear bumper in car park', 400, MINOR, 400.00",
            "'Scratched rear bumper in car park', , MINOR, 300.00",
            "'Cracked windscreen from a stone', 800, MODERATE, 1500.00",
            "'Minor scuff', 3000, MODERATE, 3000.00",
            "'Engine bay fire after collision', 500, SEVERE, 8000.00",
            "'Multiple panels', 12000, SEVERE, 12000.00",
    })
    void classifiesByKeywordsAndEstimate(String description, BigDecimal estimate, Severity expected, BigDecimal amount) {
        Claim claim = Claim.submit("CLM-X", "POL", "AB123", LocalDate.now(), description, estimate);
        Assessment assessment = provider.assess(claim);
        assertThat(assessment.severity()).isEqualTo(expected);
        assertThat(assessment.assessedAmount()).isEqualByComparingTo(amount);
    }
}
