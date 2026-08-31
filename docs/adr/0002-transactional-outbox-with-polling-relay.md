# ADR 0002: Transactional outbox with a polling relay

Status: Accepted

## Context
State changes and the events describing them must not diverge: a claim marked APPROVED whose
CLAIM_APPROVED event is lost breaks every downstream service. Publishing to Kafka inside the
database transaction is not atomic; Debezium-style CDC is the industry alternative.

## Decision
Events are written to an `outbox_event` table in the same transaction as the aggregate.
A scheduled relay claims rows with `FOR UPDATE SKIP LOCKED` (safe with many replicas),
publishes them keyed by claim id, and marks them published. The Kafka record carries the
originating W3C `traceparent`, stored per row.

## Consequences
- At-least-once delivery with per-aggregate ordering; consumers must be idempotent (ADR 0005).
- One moving part fewer than CDC (no Kafka Connect / Debezium cluster to run) at the cost of
  polling latency (hundreds of milliseconds) — acceptable for this domain.
- `outbox_pending` is a first-class health metric with a provisioned Grafana alert.
