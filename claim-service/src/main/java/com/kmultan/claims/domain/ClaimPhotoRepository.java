package com.kmultan.claims.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimPhotoRepository extends JpaRepository<ClaimPhoto, UUID> {
    List<ClaimPhoto> findByClaimIdOrderByCreatedAt(UUID claimId);
    Optional<ClaimPhoto> findByIdAndClaimId(UUID id, UUID claimId);
}
