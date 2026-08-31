# ADR 0005: Idempotent consumers via a processed-message table

Status: Accepted

## Context
The outbox gives at-least-once delivery (ADR 0002); consumer crashes and rebalances cause
redelivery. Every handler that changes state must survive seeing the same event twice.

## Decision
Each consuming service keeps a `processed_message` table (primary key = event id). The
handler inserts the id in the same transaction as its state change; a duplicate insert
aborts the handler before any effect. HTTP has the same guard: `Idempotency-Key` on claim
submission is reserved in Redis with SET NX and replayed for 24 h.

## Consequences
- Exactly-once *effect* on top of at-least-once *delivery*, per service, with one table.
- The table grows with event volume; a retention job is future work (documented, not built).
