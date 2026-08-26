package com.kmultan.claims.infrastructure.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.kmultan.claims.domain.auth.Role;
import com.kmultan.platform.security.JwtClaims;

/** The caller, as established by the verified bearer token. */
public record AuthenticatedUser(UUID id, String username, Set<Role> roles) {

    public static AuthenticatedUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return from(jwt);
    }

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(JwtClaims.PREFERRED_USERNAME),
                JwtClaims.rolesOf(jwt).stream().map(Role::valueOf).collect(Collectors.toSet()));
    }

    public boolean hasAnyRole(Role... candidates) {
        for (Role candidate : candidates) {
            if (roles.contains(candidate)) return true;
        }
        return false;
    }

    /** Staff can see every claim; a policyholder only their own. */
    public boolean isStaff() {
        return hasAnyRole(Role.ADJUSTER, Role.FINANCE, Role.ADMIN, Role.SERVICE);
    }
}
