package com.kmultan.claims.infrastructure.assessment;

import com.kmultan.claims.application.assessment.Assessment;
import com.kmultan.claims.domain.Claim;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HttpAssessmentProviderTest {

    MockWebServer server;
    HttpAssessmentProvider provider;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new HttpAssessmentProvider(WebClient.builder(),
                new AssessmentHttpProperties(server.url("/").toString(), Duration.ofMillis(500)));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static Claim claim() {
        return Claim.submit("CLM-H", "POL", "HT1", LocalDate.now(), "Scratched bumper in car park", new BigDecimal("400"));
    }

    @Test
    void usesRemoteVerdict() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"severity\":\"SEVERE\",\"assessedAmount\":9999.50,\"provider\":\"assessment-service\",\"modelVersion\":\"kw-2\",\"extra\":1}"));

        Assessment a = provider.assess(claim());

        assertThat(a.severity()).isEqualTo(Assessment.Severity.SEVERE);
        assertThat(a.assessedAmount()).isEqualByComparingTo("9999.50");
        assertThat(a.provider()).isEqualTo("assessment-service/kw-2");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/assess");
        assertThat(req.getBody().readUtf8()).contains("\"description\":\"Scratched bumper in car park\"");
    }

    @Test
    void fallsBackToHeuristicAfterRetriesWhenServiceFails() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        Assessment a = provider.assess(claim());

        assertThat(a.provider()).isEqualTo("heuristic-fallback");
        assertThat(a.severity()).isEqualTo(Assessment.Severity.MINOR);
        assertThat(server.getRequestCount()).isEqualTo(3);   // 1 call + 2 retries
    }

    @Test
    void fallsBackOnTimeout() {
        server.enqueue(new MockResponse().setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"));
        server.enqueue(new MockResponse().setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"));
        server.enqueue(new MockResponse().setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"));

        assertThat(provider.assess(claim()).provider()).isEqualTo("heuristic-fallback");
    }
}
