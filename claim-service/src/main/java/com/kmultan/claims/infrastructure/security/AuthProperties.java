package com.kmultan.claims.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "claims.auth")
public record AuthProperties(String secret, String issuer, Duration ttl, boolean seedDemoUsers, String serviceAccountPassword) {
    public AuthProperties {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalArgumentException("claims.auth.secret must be at least 32 bytes (HS256); got "
                    + (secret == null ? "null" : secret.getBytes().length + " bytes"));
        }
        if (ttl == null) ttl = Duration.ofHours(8);
        if (issuer == null) issuer = "claim-service";
    }
}
