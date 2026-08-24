package com.kmultan.payout.application;

/** Port: transactional (outbox-backed) publication of a reply. */
public interface PayoutEventPublisher {
    void publish(PayoutEvent event);
}
