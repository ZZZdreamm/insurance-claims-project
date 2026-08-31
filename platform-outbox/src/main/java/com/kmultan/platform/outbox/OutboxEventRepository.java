package com.kmultan.platform.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * {@code FOR UPDATE SKIP LOCKED} lets several service instances poll
     * concurrently without publishing the same row twice or blocking each other.
     */
    @Query(
            value =
                    """
            select * from outbox_event
            where published_at is null
            order by id
            limit :limit
            for update skip locked
            """,
            nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch(@Param("limit") int limit);

    List<OutboxEvent> findByAggregateIdOrderById(UUID aggregateId);

    long countByPublishedAtIsNull();

    /** How many distinct aggregates ever emitted each event type on a topic — the lifetime history a status snapshot cannot show. */
    @org.springframework.data.jpa.repository.Query(
            "select e.eventType, count(distinct e.aggregateId) from OutboxEvent e where e.topic = :topic group by e.eventType")
    java.util.List<Object[]> countDistinctAggregatesByEventType(
            @org.springframework.data.repository.query.Param("topic") String topic);
}
