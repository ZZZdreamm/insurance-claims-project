package com.kmultan.claims.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, String> {
    List<Policy> findByHolderAccountIdOrderByPolicyNumber(UUID holderAccountId);
}
