package com.kmultan.claims.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SubrogationCaseRepository extends JpaRepository<SubrogationCase, UUID> {
    Optional<SubrogationCase> findByClaimId(UUID claimId);

    List<SubrogationCase> findByStatusOrderByOpenedAt(SubrogationCase.Status status);

    long countByStatus(SubrogationCase.Status status);

    @Query("select coalesce(sum(s.expectedAmount), 0) from SubrogationCase s where s.status = :status")
    BigDecimal totalExpectedByStatus(SubrogationCase.Status status);

    @Query("select coalesce(sum(s.recoveredAmount), 0) from SubrogationCase s")
    BigDecimal totalRecovered();
}
