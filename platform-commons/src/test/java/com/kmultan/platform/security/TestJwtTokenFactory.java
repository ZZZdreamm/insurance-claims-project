package com.kmultan.platform.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mints tokens exactly the way claim-service does, so integration tests
 * exercise the real verification path. Defaults to the development secret in
 * every service's application.yml.
 */
public final class TestJwtTokenFactory {

    public static final String DEVELOPMENT_SECRET = "dev-only-secret-change-me-please-32-bytes-minimum";
    public static final String ISSUER = "claim-service";
    private static final Duration DEFAULT_VALIDITY = Duration.ofMinutes(10);

    private final String secret;

    public TestJwtTokenFactory() {
        this(DEVELOPMENT_SECRET);
    }

    public TestJwtTokenFactory(String secret) {
        this.secret = secret;
    }

    /** Deterministic subject per username, so ownership checks across requests line up. */
    public static UUID subjectOf(String username) {
        return UUID.nameUUIDFromBytes(("user:" + username).getBytes());
    }

    public String bearer(String username, String... roles) {
        return "Bearer " + token(username, Instant.now().plus(DEFAULT_VALIDITY), roles);
    }

    public String expiredBearer(String username, String... roles) {
        return "Bearer " + token(username, Instant.now().minus(Duration.ofMinutes(2)), roles);
    }

    public String token(String username, Instant expiresAt, String... roles) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subjectOf(username).toString())
                .issuedAt(expiresAt.minus(Duration.ofHours(1)))
                .expiresAt(expiresAt)
                .claim(JwtClaims.PREFERRED_USERNAME, username)
                .claim(JwtClaims.DISPLAY_NAME, username)
                .claim(JwtClaims.ROLES, List.of(roles))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
