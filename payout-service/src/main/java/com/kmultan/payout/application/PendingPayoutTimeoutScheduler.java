package com.kmultan.payout.application;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sweeps payouts an asynchronous provider accepted but never confirmed. */
@Component
@ConditionalOnProperty(name = "payout.gateway.mode", havingValue = "async")
public class PendingPayoutTimeoutScheduler {

    private final PayoutSaga payoutSaga;
    private final GatewayProperties properties;

    public PendingPayoutTimeoutScheduler(PayoutSaga payoutSaga, GatewayProperties properties) {
        this.payoutSaga = payoutSaga;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${payout.gateway.timeout-sweep-interval-ms:15000}")
    public void sweep() {
        payoutSaga.failTimedOutPayouts(Instant.now().minus(properties.pendingTimeout()));
    }
}
