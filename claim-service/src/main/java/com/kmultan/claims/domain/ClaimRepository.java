package com.kmultan.claims.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    Optional<Claim> findByClaimNumber(String claimNumber);
    Page<Claim> findByStatus(ClaimStatus status, Pageable pageable);
    List<Claim> findByStatusOrderByReviewDueAtAsc(ClaimStatus status);
    List<Claim> findByStatusAndReviewDueAtBeforeAndEscalatedAtIsNull(ClaimStatus status, Instant before);
    List<Claim> findByStatusAndCreatedAtBefore(ClaimStatus status, Instant before);
}
