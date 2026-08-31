# ADR 0001: Event choreography over a central orchestrator

Status: Accepted (supersedes the phase-3 Camunda 7 prototype)

## Context
The claim lifecycle spans four services. The first implementation used Camunda 7 as an
orchestrator; it worked, but the BPMN engine added ~700 MB of footprint, a second database,
and a second place where business logic lived. Compensation semantics were also subtle:
Camunda does not compensate legs of a sub-process cancelled by an outer error boundary.

## Decision
Services react to facts published on Kafka (`claims.events`, `assessment.events`,
`payout.events`) — no coordinator. Time-based behaviour (review SLA, triage timeout)
is a small scheduler inside claim-service. The payout saga compensates through
counter-events (`PAYOUT_FAILED` + `FUNDS_RELEASED`, `PAYOUT_REVERSED`).

## Consequences
- Each service owns its part of the flow; adding a consumer never touches the producer.
- There is no single place to see the process definition — mitigated by the event catalogue
  in the README and the per-claim timeline in search-service.
- Failure handling is explicit in code (dead-letter topics, retries, compensation events)
  instead of implicit in an engine.
