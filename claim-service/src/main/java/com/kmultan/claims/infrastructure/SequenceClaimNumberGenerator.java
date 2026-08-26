package com.kmultan.claims.infrastructure;

import java.time.Year;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

import com.kmultan.claims.domain.ClaimNumberGenerator;

/**
 * Backed by a Postgres sequence so numbers are unique across service instances
 * without any coordination. A gap after a rolled-back transaction is acceptable.
 */
@Component
public class SequenceClaimNumberGenerator implements ClaimNumberGenerator {

    private final EntityManager entityManager;

    public SequenceClaimNumberGenerator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public String next() {
        Number nextValue = (Number) entityManager
                .createNativeQuery("select nextval('claim_number_seq')")
                .getSingleResult();
        return "CLM-%d-%06d".formatted(Year.now().getValue(), nextValue.longValue());
    }
}
