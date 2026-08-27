package com.kmultan.payout.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Port to the external payment provider. Synchronous providers answer at once;
 * asynchronous ones answer PENDING and confirm over a webhook.
 */
public interface PaymentGateway {

    enum TransferStatus {
        COMPLETED,
        REJECTED,
        PENDING
    }

    record Result(TransferStatus status, String reference, String reason) {
        public static Result completed(String reference) {
            return new Result(TransferStatus.COMPLETED, reference, null);
        }

        public static Result rejected(String reason) {
            return new Result(TransferStatus.REJECTED, null, reason);
        }

        public static Result pending(String reference) {
            return new Result(TransferStatus.PENDING, reference, null);
        }

        public boolean success() {
            return status == TransferStatus.COMPLETED;
        }
    }

    Result transfer(UUID claimId, String policyNumber, BigDecimal amount);

    void reverse(UUID claimId, String reference);
}
