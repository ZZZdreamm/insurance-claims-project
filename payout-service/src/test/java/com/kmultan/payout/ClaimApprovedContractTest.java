package com.kmultan.payout;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kmultan.payout.application.ClaimEventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-driven contract for the CLAIM_APPROVED event. This test writes
 * {@code contracts/pacts/payout-service-claim-service.json}; claim-service's
 * build verifies that its real serialiser produces a message satisfying it.
 * Only the fields payout-service actually reads are pinned — everything else
 * is free to change.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "claim-service", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V3)
class ClaimApprovedContractTest {

    @Pact(consumer = "payout-service", provider = "claim-service")
    MessagePact claimApproved(MessagePactBuilder builder) {
        PactDslJsonBody body = new PactDslJsonBody()
                .uuid("eventId")
                .stringValue("eventType", "CLAIM_APPROVED")
                .uuid("claimId")
                .stringMatcher("occurredAt", "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z", "2026-08-24T10:00:00Z")
                .object("claim")
                    .stringMatcher("claimNumber", "CLM-\\d{4}-\\d{6}", "CLM-2026-000042")
                    .stringType("policyNumber", "POL-123")
                    .decimalType("approvedAmount", 1400.00)
                .closeObject()
                .asBody();
        return builder.expectsToReceive("a CLAIM_APPROVED event").withContent(body).toPact();
    }

    @Test
    @PactTestFor(pactMethod = "claimApproved")
    void parsesTheEventItDependsOn(List<Message> messages) throws Exception {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ClaimEventEnvelope e = json.readValue(messages.get(0).contentsAsString(), ClaimEventEnvelope.class);
        assertThat(e.eventType()).isEqualTo(ClaimEventEnvelope.CLAIM_APPROVED);
        assertThat(e.claim().approvedAmount()).isEqualByComparingTo("1400.00");
        assertThat(e.claim().policyNumber()).isNotBlank();
    }
}
