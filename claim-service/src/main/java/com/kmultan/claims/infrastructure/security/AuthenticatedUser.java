package com.kmultan.claims.infrastructure.security;

import com.kmultan.claims.domain.auth.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The authenticated caller, read from the verified JWT. */
public record CurrentUser(UUID id, String username, Set<Role> roles) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated user");
        }
        return from(jwt);
    }

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(JwtTokens.USERNAME_CLAIM),
                JwtTokens.rolesOf(jwt).stream().map(Role::valueOf).collect(Collectors.toSet()));
    }

    public boolean has(Role... any) {
        for (Role r : any) if (roles.contains(r)) return true;
        return false;
    }

    /** Staff can see every claim; a policyholder only their own. */
    public boolean isStaff() {
        return has(Role.ADJUSTER, Role.FINANCE, Role.ADMIN, Role.SERVICE);
    }
}
