# ADR 0004: Search projection with external versioning

Status: Accepted

## Context
search-service projects claim events into Elasticsearch (CQRS read side). Kafka guarantees
per-claim order within a partition, but redeliveries and replays can present an old event
after a newer one has been indexed.

## Decision
The `claims` index uses Elasticsearch external versioning, with the outbox sequence number as
the version. A stale event fails the write with a version conflict and is deliberately
dropped. The `claim-events` index is append-only with the event id as document id, so
replays overwrite instead of duplicating.

## Consequences
- The projection is safe to rebuild by replaying the topic from offset zero.
- Version conflicts surface as `ResponseException` (not only `ElasticsearchException`) —
  both are handled; this was found by test, not in production.
