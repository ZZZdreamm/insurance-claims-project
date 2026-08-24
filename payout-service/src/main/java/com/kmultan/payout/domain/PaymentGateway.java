package com.kmultan.payout.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Port to the external payment provider. */
public interface PaymentGateway {
    record Result(boolean success, String reference, String reason) {
        public static Result ok(String reference) { return new Result(true, reference, null); }
        public static Result failed(String reason) { return new Result(false, null, reason); }
    }

    Result transfer(UUID claimId, String policyNumber, BigDecimal amount);

    void reverse(UUID claimId, String reference);
}
