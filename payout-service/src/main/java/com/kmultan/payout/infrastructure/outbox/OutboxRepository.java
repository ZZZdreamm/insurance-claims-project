package com.kmultan.payout.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * {@code FOR UPDATE SKIP LOCKED} lets several service instances poll
     * concurrently without publishing the same row twice or blocking each other.
     */
    @Query(value = """
            select * from outbox_event
            where published_at is null
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch(@Param("limit") int limit);

    List<OutboxEvent> findByAggregateIdOrderById(UUID aggregateId);

    long countByPublishedAtIsNull();
}
