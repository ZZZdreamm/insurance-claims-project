package com.kmultan.claims.infrastructure.security;

import com.kmultan.claims.domain.auth.Role;
import com.kmultan.claims.domain.auth.UserAccount;

import java.util.EnumSet;
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
}
