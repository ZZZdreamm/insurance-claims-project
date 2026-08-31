# Architecture Decision Records

Numbered, immutable records of the decisions that shaped this platform. A new decision that
changes an old one gets a new ADR that supersedes it — history is never rewritten.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-event-choreography-over-orchestration.md) | Event choreography over a central orchestrator | Accepted |
| [0002](0002-transactional-outbox-with-polling-relay.md) | Transactional outbox with a polling relay (over CDC) | Accepted |
| [0003](0003-self-issued-hs256-jwt.md) | Self-issued HS256 JWTs (over Keycloak) | Accepted |
| [0004](0004-elasticsearch-projection-with-external-versioning.md) | Search projection with external versioning | Accepted |
| [0005](0005-idempotent-consumers-via-processed-message-table.md) | Idempotent consumers via a processed-message table | Accepted |
| [0006](0006-scheduler-leader-election-with-shedlock.md) | Scheduler leader election with ShedLock | Accepted |
| [0007](0007-asynchronous-payment-gateway.md) | Asynchronous payment gateway with webhook + timeout compensation | Accepted |
| [0008](0008-free-single-machine-constraint.md) | Free, single-machine constraint and what it cut | Accepted |
