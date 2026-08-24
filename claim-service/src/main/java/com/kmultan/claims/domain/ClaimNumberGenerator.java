package com.kmultan.claims.domain;

/** Produces human-readable claim numbers such as CLM-2026-000042. */
public interface ClaimNumberGenerator {
    String next();
}
