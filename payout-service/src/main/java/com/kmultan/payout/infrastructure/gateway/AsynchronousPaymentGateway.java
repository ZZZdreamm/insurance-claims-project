package com.kmultan.payout.infrastructure.gateway;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.kmultan.payout.application.GatewayProperties;
import com.kmultan.payout.domain.PaymentGateway;

/**
 * Talks to the payment-gateway-simulator the way a real provider integration
 * works: submit the transfer with a callback URL, get 202 ACCEPTED with a
 * transfer id, and treat the payout as PENDING until the webhook (or the
 * timeout sweep) settles it. Enabled with {@code payout.gateway.mode=async}.
 */
@Component
@ConditionalOnProperty(name = "payout.gateway.mode", havingValue = "async")
public class AsynchronousPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(AsynchronousPaymentGateway.class);

    private final RestClient restClient;
    private final GatewayProperties properties;

    public AsynchronousPaymentGateway(GatewayProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.url()).build();
    }

    record TransferAccepted(UUID transferId, String status) {}

    @Override
    public Result transfer(UUID claimId, String policyNumber, BigDecimal amount) {
        TransferAccepted accepted = restClient
                .post()
                .uri("/transfers")
                .body(Map.of(
                        "claimId",
                        claimId.toString(),
                        "amount",
                        amount,
                        "callbackUrl",
                        properties.callbackBaseUrl() + "/api/v1/payments/callback"))
                .retrieve()
                .body(TransferAccepted.class);
        if (accepted == null) {
            return Result.rejected("Empty response from the payment gateway");
        }
        log.info("Gateway accepted transfer {} for claim {} ({})", accepted.transferId(), claimId, amount);
        return Result.pending(accepted.transferId().toString());
    }

    @Override
    public void reverse(UUID claimId, String reference) {
        log.info("Requested reversal of transfer {} for claim {}", reference, claimId);
    }
}
