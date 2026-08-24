package com.kmultan.claims.domain;

import java.util.UUID;

public class ClaimNotFoundException extends RuntimeException {
    public ClaimNotFoundException(UUID id) {
        super("Claim %s not found".formatted(id));
    }
}
