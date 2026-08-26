package com.kmultan.platform.security;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;

/** Names of the custom claims claim-service puts into its tokens. */
public final class JwtClaims {

    public static final String ROLES = "roles";
    public static final String PREFERRED_USERNAME = "preferred_username";
    public static final String DISPLAY_NAME = "name";
    public static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    private JwtClaims() {}

    public static List<String> rolesOf(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES);
        return roles == null ? List.of() : roles;
    }
}
