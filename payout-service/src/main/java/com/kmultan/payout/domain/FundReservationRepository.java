package com.kmultan.payout.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FundReservationRepository extends JpaRepository<FundReservation, UUID> {
    long countByStatus(FundReservation.Status status);

    org.springframework.data.domain.Page<FundReservation> findByClaimIdIn(
            java.util.Collection<UUID> claimIds, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(r.amount), 0) from FundReservation r where r.status = :status")
    java.math.BigDecimal sumAmountByStatus(FundReservation.Status status);
}
