package com.kmultan.gateway;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A payment provider the way real ones behave: the transfer request is only
 * <em>accepted</em>; the outcome arrives later on the caller's webhook. Rules
 * mirror the platform's deterministic drills — amounts ending in .99 are
 * rejected, amounts ending in .77 are accepted and then never confirmed
 * (exercising the caller's timeout path).
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);
    private static final BigDecimal REJECTED_CENTS = new BigDecimal("0.99");
    private static final BigDecimal NEVER_CONFIRMED_CENTS = new BigDecimal("0.77");

    private final ScheduledExecutorService callbackExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<UUID, TransferRequest> transfers = new ConcurrentHashMap<>();
    private final RestClient restClient = RestClient.create();
    private final long callbackDelayMillis;
    private final String callbackToken;

    public TransferController(
            @Value("${gateway.callback-delay-millis:700}") long callbackDelayMillis,
            @Value("${gateway.callback-token}") String callbackToken) {
        this.callbackDelayMillis = callbackDelayMillis;
        this.callbackToken = callbackToken;
    }

    public record TransferRequest(
            @NotBlank String claimId, @NotNull @Positive BigDecimal amount, @NotBlank String callbackUrl) {}

    public record TransferAccepted(UUID transferId, String status) {}

    public record TransferCallback(UUID transferId, String status, String reason) {}

    @PostMapping
    public ResponseEntity<TransferAccepted> accept(@Valid @RequestBody TransferRequest request) {
        UUID transferId = UUID.randomUUID();
        transfers.put(transferId, request);
        if (request.amount().remainder(BigDecimal.ONE).compareTo(NEVER_CONFIRMED_CENTS) != 0) {
            callbackExecutor.schedule(() -> confirm(transferId, request), callbackDelayMillis, TimeUnit.MILLISECONDS);
        } else {
            log.warn(
                    "Transfer {} for claim {} accepted and deliberately never confirmed",
                    transferId,
                    request.claimId());
        }
        log.info("Accepted transfer {} for claim {} ({})", transferId, request.claimId(), request.amount());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new TransferAccepted(transferId, "ACCEPTED"));
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<TransferRequest> status(@PathVariable UUID transferId) {
        TransferRequest request = transfers.get(transferId);
        return request == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(request);
    }

    private void confirm(UUID transferId, TransferRequest request) {
        boolean rejected = request.amount().remainder(BigDecimal.ONE).compareTo(REJECTED_CENTS) == 0;
        TransferCallback callback = new TransferCallback(
                transferId, rejected ? "REJECTED" : "COMPLETED", rejected ? "Insufficient funds at provider" : null);
        try {
            restClient
                    .post()
                    .uri(request.callbackUrl())
                    .header("X-Gateway-Token", callbackToken)
                    .body(callback)
                    .retrieve()
                    .toBodilessEntity();
            log.info(
                    "Callback {} for transfer {} delivered to {}",
                    callback.status(),
                    transferId,
                    request.callbackUrl());
        } catch (RuntimeException exception) {
            log.error("Callback for transfer {} failed: {}", transferId, exception.toString());
        }
    }
}
