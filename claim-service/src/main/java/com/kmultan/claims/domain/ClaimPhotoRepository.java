package com.kmultan.claims.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimPhotoRepository extends JpaRepository<ClaimPhoto, UUID> {
    List<ClaimPhoto> findByClaimIdOrderByCreatedAt(UUID claimId);

    Optional<ClaimPhoto> findByIdAndClaimId(UUID id, UUID claimId);

    boolean existsByContentHashAndClaimIdNot(String contentHash, java.util.UUID claimId);
}
