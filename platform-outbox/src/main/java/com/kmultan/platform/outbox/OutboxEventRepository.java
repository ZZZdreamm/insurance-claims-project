package com.kmultan.platform.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * {@code FOR UPDATE SKIP LOCKED} lets several relays poll concurrently without
     * double-publishing or blocking — but on its own it breaks per-aggregate ordering:
     * while relay A holds an aggregate's row N, relay B would skip it and happily
     * publish row N+1 first. The {@code not exists} guard therefore offers only the
     * HEAD row of each aggregate: followers become eligible when their predecessor's
     * publish has committed, never before. A crash between the Kafka send and the
     * commit redelivers the head (consumers are idempotent) — still in order.
     */
    @Query(
            value =
                    """
            select * from outbox_event o
            where o.published_at is null
              and not exists (
                  select 1 from outbox_event previous
                  where previous.aggregate_id = o.aggregate_id
                    and previous.published_at is null
                    and previous.id < o.id)
            order by o.id
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
