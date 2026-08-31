package com.kmultan.payout.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payout.gateway")
public record GatewayProperties(
        String mode, String url, String callbackBaseUrl, String callbackToken, Duration pendingTimeout) {
    public GatewayProperties {
        if (pendingTimeout == null) pendingTimeout = Duration.ofMinutes(2);
    }
}
