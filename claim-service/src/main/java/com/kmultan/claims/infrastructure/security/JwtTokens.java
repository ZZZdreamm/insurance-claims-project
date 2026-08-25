package com.kmultan.claims.infrastructure.security;

import com.kmultan.claims.domain.auth.UserAccount;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;

/**
 * Self-issued HS256 JWTs. The secret is shared by every service that must
 * verify tokens (payout-service, search-service). Asymmetric keys (RS256 +
 * JWKS endpoint) would let other services verify without holding the secret;
 * for a single-team platform a shared secret from the environment is the
 * simpler, honest choice.
 */
@Component
public class JwtTokens {

    public static final String ROLES_CLAIM = "roles";
    public static final String USERNAME_CLAIM = "preferred_username";
    public static final String NAME_CLAIM = "name";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AuthProperties props;

    public JwtTokens(AuthProperties props) {
        this.props = props;
        SecretKey key = new SecretKeySpec(props.secret().getBytes(), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        this.decoder = nimbus;
    }

    public record Issued(String token, Instant expiresAt) {}

    public Issued issue(UserAccount user) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(exp)
                .claim(USERNAME_CLAIM, user.getUsername())
                .claim(NAME_CLAIM, user.getDisplayName())
                .claim(ROLES_CLAIM, user.getRoles().stream().map(Enum::name).toList())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Issued(token, exp);
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    static List<String> rolesOf(org.springframework.security.oauth2.jwt.Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        return roles == null ? List.of() : roles;
    }
}
