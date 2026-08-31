package com.kmultan.claims.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClaimReserveRepository extends JpaRepository<ClaimReserve, UUID> {

    Optional<ClaimReserve> findByClaimId(UUID claimId);

    long countByStatus(ClaimReserve.Status status);

    @Query("select coalesce(sum(r.currentAmount), 0) from ClaimReserve r where r.status = :status")
    BigDecimal totalByStatus(ClaimReserve.Status status);

    /** Open reserve exposure grouped by claim severity (null severity = not yet assessed). */
    @Query(
            """
            select c.severity, count(r), coalesce(sum(r.currentAmount), 0)
            from ClaimReserve r join Claim c on c.id = r.claimId
            where r.status = com.kmultan.claims.domain.ClaimReserve.Status.OPEN
            group by c.severity
            """)
    List<Object[]> openExposureBySeverity();
}
