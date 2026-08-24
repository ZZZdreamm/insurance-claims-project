package com.kmultan.claims.application.payout;

/** Port: durable, transactional dispatch of a payout command (outbox-backed). */
public interface PayoutCommandSender {
    void send(PayoutCommand command);
}
