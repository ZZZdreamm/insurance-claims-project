package com.kmultan.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared HS256 secret and issuer: claim-service signs tokens with it, every service verifies with it. */
@ConfigurationProperties(prefix = "platform.security")
public record PlatformSecurityProperties(String jwtSecret, String jwtIssuer) {

    public static final int MINIMUM_SECRET_BYTES = 32;

    public PlatformSecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes().length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("platform.security.jwt-secret must be at least " + MINIMUM_SECRET_BYTES
                    + " bytes for HS256; got " + (jwtSecret == null ? "null" : jwtSecret.getBytes().length + " bytes"));
        }
        if (jwtIssuer == null || jwtIssuer.isBlank()) {
            jwtIssuer = "claim-service";
        }
    }
}
