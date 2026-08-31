package com.kmultan.claims.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimPaymentRepository extends JpaRepository<ClaimPayment, UUID> {
    List<ClaimPayment> findByClaimIdOrderByIssuedAt(UUID claimId);
}
