package com.kmultan.claims.infrastructure.security;

import java.time.Instant;

import javax.crypto.SecretKey;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.kmultan.claims.domain.auth.UserAccount;
import com.kmultan.platform.security.JwtClaims;
import com.kmultan.platform.security.PlatformSecurityProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Issues the platform's HS256 tokens. Verification lives in platform-commons so
 * every service checks tokens the same way; only claim-service signs them.
 * Asymmetric keys (RS256 + JWKS) are the upgrade if a third party must verify
 * without holding the secret.
 */
@Service
public class JwtTokenService {

    public record IssuedToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final PlatformSecurityProperties securityProperties;
    private final AuthenticationProperties authenticationProperties;

    public JwtTokenService(
            SecretKey jwtSecretKey,
            PlatformSecurityProperties securityProperties,
            AuthenticationProperties authenticationProperties) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
        this.securityProperties = securityProperties;
        this.authenticationProperties = authenticationProperties;
    }

    public IssuedToken issue(UserAccount account) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(authenticationProperties.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.jwtIssuer())
                .subject(account.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(JwtClaims.PREFERRED_USERNAME, account.getUsername())
                .claim(JwtClaims.DISPLAY_NAME, account.getDisplayName())
                .claim(
                        JwtClaims.ROLES,
                        account.getRoles().stream().map(Enum::name).toList())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(value, expiresAt);
    }
}
