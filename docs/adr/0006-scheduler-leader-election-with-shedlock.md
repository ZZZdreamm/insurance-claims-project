# ADR 0006: Scheduler leader election with ShedLock

Status: Accepted

## Context
claim-service replicas each run the SLA/triage-timeout scheduler. The sweeps are idempotent
(guarded by claim state + optimistic locking), but running them on every replica multiplies
load and surfaces as optimistic-lock noise. The outbox relay is already concurrency-safe
via `SKIP LOCKED` and needs nothing.

## Decision
ShedLock with a JDBC provider (a `shedlock` row in the same Postgres) elects exactly one
runner per tick. Quartz or a leader-elected singleton deployment were rejected as heavier.

## Consequences
- `docker compose up --scale claim-service=3` needs no scheduler configuration.
- A lock expiring mid-run readmits concurrency — harmless here because the sweeps stay
  idempotent; ShedLock reduces waste, it does not carry correctness.
