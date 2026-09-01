package com.kmultan.claims.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SubrogationCaseRepository extends JpaRepository<SubrogationCase, UUID> {
    Optional<SubrogationCase> findByClaimId(UUID claimId);

    org.springframework.data.domain.Page<SubrogationCase> findByStatus(
            SubrogationCase.Status status, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<SubrogationCase> findByStatusAndLiablePartyContainingIgnoreCase(
            SubrogationCase.Status status, String liableParty, org.springframework.data.domain.Pageable pageable);

    long countByStatus(SubrogationCase.Status status);

    @Query("select coalesce(sum(s.expectedAmount), 0) from SubrogationCase s where s.status = :status")
    BigDecimal totalExpectedByStatus(SubrogationCase.Status status);

    @Query("select coalesce(sum(s.recoveredAmount), 0) from SubrogationCase s")
    BigDecimal totalRecovered();
}
