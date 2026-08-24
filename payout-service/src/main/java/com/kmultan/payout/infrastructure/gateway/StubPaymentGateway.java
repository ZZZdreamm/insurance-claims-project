package com.kmultan.payout.infrastructure.gateway;

import com.kmultan.payout.domain.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deterministic stand-in for a payment provider. Amounts ending in .99 are
 * rejected, which gives demos and tests a reproducible way to trigger the
 * saga's compensation path.
 */
@Component
public class StubPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);

    @Override
    public Result transfer(UUID claimId, String policyNumber, BigDecimal amount) {
        if (amount.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.99")) == 0) {
            return Result.failed("Payment provider rejected the transfer");
        }
        String reference = "PAY-" + claimId.toString().substring(0, 8).toUpperCase();
        log.info("Transferred {} for claim {} ({})", amount, claimId, reference);
        return Result.ok(reference);
    }

    @Override
    public void reverse(UUID claimId, String reference) {
        log.info("Reversed transfer {} for claim {}", reference, claimId);
    }
}
