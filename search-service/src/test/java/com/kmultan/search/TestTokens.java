package com.kmultan.search;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mints tokens the way claim-service does, with the dev secret from application.yml. */
final class TestTokens {
    private TestTokens() {}
    static final String SECRET = "dev-only-secret-change-me-please-32-bytes-minimum";

    static String bearer(String username, String... roles) {
        NimbusJwtEncoder enc = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("claim-service").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("preferred_username", username).claim("roles", List.of(roles)).build();
        return "Bearer " + enc.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
