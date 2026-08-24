package com.kmultan.claims.domain;

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(ClaimStatus from, ClaimStatus to) {
        super("Cannot transition claim from %s to %s".formatted(from, to));
    }
}
