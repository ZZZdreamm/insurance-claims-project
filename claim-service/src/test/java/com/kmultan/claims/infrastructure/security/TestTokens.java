package com.kmultan.claims.infrastructure.security;

import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.domain.auth.UserAccount;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Mints real tokens with the test profile's secret — the ITs exercise the actual verification path. */
public final class TestTokens {
    private TestTokens() {}

    public static UUID idOf(String username) {
        return UUID.nameUUIDFromBytes(("user:" + username).getBytes());
    }

    public static String bearer(JwtTokens tokens, String username, Role... roles) {
        UserAccount u = new UserAccount(username, "n/a", username, EnumSet.copyOf(java.util.List.of(roles))) {
            @Override public UUID getId() { return idOf(username); }
        };
        return "Bearer " + tokens.issue(u).token();
    }

    private static String sign(String secret, String username, Instant exp, Role... roles) {
        NimbusJwtEncoder enc = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret.getBytes(), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("claim-service").subject(idOf(username).toString())
                .issuedAt(exp.minusSeconds(3600)).expiresAt(exp)
                .claim(JwtTokens.USERNAME_CLAIM, username).claim(JwtTokens.NAME_CLAIM, username)
                .claim(JwtTokens.ROLES_CLAIM, java.util.Arrays.stream(roles).map(Enum::name).toList()).build();
        return "Bearer " + enc.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public static String expiredBearer(JwtTokens tokens, String username, Role... roles) {
        return sign("dev-only-secret-change-me-please-32-bytes-minimum", username, Instant.now().minusSeconds(120), roles);
    }

    public static String bearerSignedWith(String secret, String username, Role... roles) {
        return sign(secret, username, Instant.now().plusSeconds(600), roles);
    }
}
