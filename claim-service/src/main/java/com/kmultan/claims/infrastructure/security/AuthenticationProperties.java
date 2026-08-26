package com.kmultan.claims.infrastructure.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token issuing and demo-account settings; the signing secret itself lives in {@code platform.security}. */
@ConfigurationProperties(prefix = "claims.auth")
public record AuthenticationProperties(Duration tokenTtl, boolean seedDemoUsers, String serviceAccountPassword) {

    public AuthenticationProperties {
        if (tokenTtl == null) tokenTtl = Duration.ofHours(8);
    }
}
