package com.kmultan.payout.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {
    java.util.Optional<Payout> findByReference(String reference);

    java.util.List<Payout> findByStatusAndUpdatedAtBefore(Payout.Status status, java.time.Instant before);
}
