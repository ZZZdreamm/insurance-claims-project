package com.kmultan.claims.infrastructure.assessment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.application.assessment.AssessmentProvider;
import com.kmultan.claims.domain.Claim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Calls the Python assessment-service. Bounded by a timeout and two retries;
 * if the service is down the claim still moves on using the heuristic, with
 * the provider name recorded so the degradation is visible in the process
 * variables. Selected with {@code claims.assessment.provider=http}.
 */
@Component
@ConditionalOnProperty(name = "claims.assessment.provider", havingValue = "http")
public class HttpAssessmentProvider implements AssessmentProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpAssessmentProvider.class);

    private final WebClient client;
    private final HeuristicAssessmentProvider fallback = new HeuristicAssessmentProvider();
    private final Duration timeout;

    public HttpAssessmentProvider(WebClient.Builder builder, AssessmentHttpProperties props) {
        this.client = builder.baseUrl(props.url()).build();
        this.timeout = props.timeout();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String severity, BigDecimal assessedAmount, String provider, String modelVersion) {}

    @Override
    public Assessment assess(Claim claim) {
        try {
            Response r = client.post().uri("/assess")
                    .bodyValue(Map.of(
                            "claimId", claim.getId().toString(),
                            "description", claim.getDescription(),
                            "estimatedAmount", claim.getEstimatedAmount() == null ? BigDecimal.ZERO : claim.getEstimatedAmount()))
                    .retrieve()
                    .bodyToMono(Response.class)
                    .timeout(timeout)
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(200)))
                    .block();
            if (r == null) {
                throw new IllegalStateException("empty response");
            }
            return new Assessment(Assessment.Severity.valueOf(r.severity()), r.assessedAmount(),
                    r.provider() + "/" + r.modelVersion());
        } catch (Exception e) {
            log.warn("assessment-service unavailable for claim {} ({}); falling back to heuristic", claim.getId(), e.toString());
            Assessment h = fallback.assess(claim);
            return new Assessment(h.severity(), h.assessedAmount(), "heuristic-fallback");
        }
    }

    // keeps a test-friendly constructor path for the fallback name
    static UUID id(Claim c) { return c.getId(); }
}
