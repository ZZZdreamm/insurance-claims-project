package com.kmultan.payout.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {
    java.util.Optional<Payout> findByReference(String reference);

    java.util.List<Payout> findTop100ByReferenceContainingIgnoreCase(String reference);

    java.util.List<Payout> findByStatusAndUpdatedAtBefore(Payout.Status status, java.time.Instant before);

    long countByStatus(Payout.Status status);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(p.amount), 0) from Payout p where p.status = :status")
    java.math.BigDecimal sumAmountByStatus(Payout.Status status);
}
