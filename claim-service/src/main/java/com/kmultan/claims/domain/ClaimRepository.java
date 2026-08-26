package com.kmultan.claims.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    Optional<Claim> findByClaimNumber(String claimNumber);

    Page<Claim> findByStatus(ClaimStatus status, Pageable pageable);

    Page<Claim> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<Claim> findByOwnerIdAndStatus(UUID ownerId, ClaimStatus status, Pageable pageable);

    List<Claim> findByStatusOrderByReviewDueAtAsc(ClaimStatus status);

    List<Claim> findByStatusAndReviewDueAtBeforeAndEscalatedAtIsNull(ClaimStatus status, Instant before);

    List<Claim> findByStatusAndCreatedAtBefore(ClaimStatus status, Instant before);

    long countByStatus(ClaimStatus status);

    long countByStatusAndEscalatedAtIsNotNull(ClaimStatus status);

    interface StatusCount {
        ClaimStatus getStatus();

        long getCount();
    }

    interface DayCount {
        java.time.LocalDate getDay();

        long getCount();
    }

    interface SeverityCount {
        Severity getSeverity();

        long getCount();
    }

    @Query("select c.status as status, count(c) as count from Claim c group by c.status")
    List<StatusCount> countByStatusGrouped();

    @Query(
            "select c.severity as severity, count(c) as count from Claim c where c.severity is not null group by c.severity")
    List<SeverityCount> countBySeverityGrouped();

    @Query(
            value = "select cast(created_at as date) as day, count(*) as count from claim"
                    + " where created_at >= :since group by day order by day",
            nativeQuery = true)
    List<DayCount> countSubmittedPerDay(@Param("since") Instant since);

    @Query(
            "select coalesce(sum(c.approvedAmount), 0) from Claim c where c.status = com.kmultan.claims.domain.ClaimStatus.PAID")
    java.math.BigDecimal sumPaid();

    @Query(
            "select coalesce(sum(c.approvedAmount), 0) from Claim c where c.status = com.kmultan.claims.domain.ClaimStatus.APPROVED")
    java.math.BigDecimal sumApprovedAwaitingPayout();

    @Query(
            value = "select avg(extract(epoch from (paid_at - created_at))) from claim where paid_at is not null",
            nativeQuery = true)
    Double averageSecondsFromSubmissionToPayment();

    @Query(
            value =
                    "select avg(extract(epoch from (assessed_at - created_at))) from claim where assessed_at is not null",
            nativeQuery = true)
    Double averageSecondsToAssessment();
}
