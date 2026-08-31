package com.kmultan.payout.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kmultan.payout.application.GatewayProperties;
import com.kmultan.payout.application.PayoutSaga;

/**
 * Webhook for the asynchronous payment provider. Machine-to-machine, so it is
 * outside the JWT chain and authenticated by a shared token instead; the
 * handler itself is idempotent, so redelivered callbacks are harmless. Any
 * payout-service replica can take the callback — state lives in Postgres.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentCallbackController {

    private final PayoutSaga payoutSaga;
    private final GatewayProperties properties;

    public PaymentCallbackController(PayoutSaga payoutSaga, GatewayProperties properties) {
        this.payoutSaga = payoutSaga;
        this.properties = properties;
    }

    public record TransferCallback(@NotNull UUID transferId, @NotBlank String status, String reason) {}

    @PostMapping("/callback")
    public ResponseEntity<Void> onCallback(
            @RequestHeader(value = "X-Gateway-Token", required = false) String token,
            @Valid @RequestBody TransferCallback callback) {
        if (token == null || !constantTimeEquals(token, properties.callbackToken())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        payoutSaga.onGatewayCallback(
                callback.transferId().toString(), "COMPLETED".equals(callback.status()), callback.reason());
        return ResponseEntity.noContent().build();
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
