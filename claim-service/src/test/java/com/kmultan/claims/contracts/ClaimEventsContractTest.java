package com.kmultan.claims.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.event.ClaimEvent;
import com.kmultan.claims.domain.event.ClaimEventType;

import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;

/**
 * Provider side of the consumer-driven contract in {@code contracts/pacts}:
 * proves that the JSON this service really emits for CLAIM_APPROVED satisfies
 * what payout-service relies on. No broker needed — it verifies the serialiser.
 */
@Provider("claim-service")
@PactFolder("../contracts/pacts")
class ClaimEventsContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @PactVerifyProvider("a CLAIM_APPROVED event")
    String claimApproved() throws Exception {
        Claim claim = Claim.submit(
                "CLM-2026-000042",
                "POL-123",
                "WA12345",
                LocalDate.now(),
                "Rear bumper dented in a parking lot",
                new BigDecimal("1200.00"));
        claim.completeAssessment(com.kmultan.claims.domain.Severity.MODERATE, new BigDecimal("1500.00"), "test", null);
        claim.approve(
                new Claim.Settlement(new BigDecimal("1400.00"), new BigDecimal("1400.00"), BigDecimal.ZERO),
                new BigDecimal("1400.00"));
        return objectMapper.writeValueAsString(
                ClaimEvent.of(ClaimEventType.CLAIM_APPROVED, claim, List.of(UUID.randomUUID())));
    }
}
