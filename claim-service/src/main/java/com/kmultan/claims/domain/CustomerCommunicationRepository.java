package com.kmultan.claims.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerCommunicationRepository extends JpaRepository<CustomerCommunication, UUID> {
    List<CustomerCommunication> findByClaimIdOrderBySentAt(UUID claimId);
}
