# Motor Insurance Claims Platform

A claims-handling platform for motor insurance, built as a small set of services that
demonstrate the patterns that actually matter in distributed systems: a transactional
outbox, a saga with compensation, idempotent consumers, and end-to-end tracing —
all runnable on one laptop for free.

[![CI](https://github.com/kmultan/insurance-claims-project/actions/workflows/ci.yml/badge.svg)](https://github.com/kmultan/insurance-claims-project/actions/workflows/ci.yml)

## The flow

A policyholder uploads photos and a description of the damage. The system ingests the
claim, runs automated triage (a small local vision model estimates severity), routes it
through a BPMN process with SLA timers and a human adjuster review, then executes the
payout as a distributed saga that compensates if any leg fails.

## Status: Phase 5 — observability, Helm, CI

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | `claim-service`: claim aggregate, Postgres, REST + validation, Testcontainers, Compose | **done** |
| 2 | Kafka + transactional outbox + Elasticsearch read projection (`search-service`) | **done** |
| 3 | Embedded Camunda 7 process: automated triage, adjuster user task, SLA timer; Next.js console | **done** |
| 4 | Payout saga with BPMN compensation, `payout-service` (idempotent consumer, DLQ replay), FastAPI `assessment-service` | **done** |
| 5 | Prometheus/Grafana/Loki/Tempo, trace context through outbox + Kafka headers, business metrics, Helm chart + kind script, Jenkinsfile, k6 | **done** |
| 6 | Optional: drag-and-drop upload page, WebFlux document streaming | planned |

## Run it

Requirements: Java 21, Maven 3.8+, Docker.

```bash
# Postgres + Kafka + claim-service
docker compose --profile core up -d --build

# add Elasticsearch + search-service
docker compose --profile core --profile search up -d --build

# add the adjuster console (http://localhost:3000); Camunda Cockpit is at http://localhost:8080/camunda (demo/demo)
docker compose --profile core --profile console up -d --build

# add the Python triage service and point claim-service at it (otherwise the heuristic runs in-process)
CLAIMS_ASSESSMENT_PROVIDER=http docker compose --profile core --profile ml up -d --build

# add Prometheus + Grafana (http://localhost:3001) + Loki + Tempo; copy .env.example to .env first so
# the services export traces and ship logs
cp .env.example .env && docker compose --profile core --profile observability up -d --build
```

Open Grafana → *Claims platform* dashboard for submissions, status transitions, outbox lag,
p95 latencies. Explore → Tempo → search `service.name = claim-service` and open a trace: the HTTP
submit, the outbox relay, the search-service and payout-service consumers and the assessment
call are one trace. Loki log lines carry `traceId=`, which Grafana turns into a link to the trace.

### Kubernetes (kind)

```bash
./deploy/kind/up.sh                                   # builds images, loads them into kind, helm install
kubectl port-forward svc/claim-service 8080:8080 &
kubectl port-forward svc/grafana 3001:3000 &
```

The chart (`deploy/helm/claims-platform`) deploys the five services plus single-replica,
`emptyDir`-backed Postgres/Kafka/Elasticsearch and the observability stack — enough for a demo
cluster, and explicitly *not* how the infrastructure would be run in production (that is what
CloudNativePG, Strimzi and ECK are for). `helm lint` and `helm template` run in CI.

### Load test

```bash
k6 run --vus 20 --duration 60s perf/k6-submit.js     # thresholds: p95 < 300 ms, error rate < 1%
```

Numbers are not quoted here on purpose: they depend on the laptop. Run it before and after a change
(e.g. outbox batch size, Hikari pool size) and put the two `http_req_duration` summaries side by side.

Approving a claim starts the payout saga. Two deterministic ways to make it fail, for demos:
approve an amount ending in `.99` (payment provider rejects → reservation is released) or above
`50000` (reservation rejected → nothing to compensate). Watch it in Cockpit, or:

```bash
curl -s localhost:8080/api/v1/claims/$ID | jq '{status, approvedAmount, payoutFailureReason}'
curl -s -X POST localhost:8082/api/v1/dlq/replay     # re-drive dead-lettered payout commands
```

```bash
# (no-op placeholder to keep the block structure)

# or run a service from source against the Compose infrastructure
docker compose --profile core up -d postgres kafka
cd claim-service && SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:29092 mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Submit a claim:

```bash
curl -s -X POST localhost:8080/api/v1/claims -H 'Content-Type: application/json' -d '{
  "policyNumber": "POL-123",
  "plateNumber": "WA 12345",
  "incidentDate": "2026-08-20",
  "description": "Rear-ended at a red light, bumper and tail light damaged",
  "estimatedAmount": 2500.00
}' | jq
```

Submitting starts the `claim-handling` BPMN process: the job executor runs the automated
assessment, then a review task appears for the `adjusters` group:

```bash
curl -s localhost:8080/api/v1/tasks | jq                       # open reviews with claim summary, severity, due date
curl -s -X POST localhost:8080/api/v1/tasks/$TASK/claim -H 'Content-Type: application/json' -d '{"assignee":"alice"}'
curl -s -X POST localhost:8080/api/v1/tasks/$TASK/complete -H 'Content-Type: application/json' \
     -d '{"decision":"APPROVE","approvedAmount":2000}'          # or {"decision":"REJECT","reason":"..."}
```

The same lifecycle is exposed directly (`POST /api/v1/claims/{id}/withdraw` etc.) for
operational use; illegal transitions return `409` as RFC 9457 `application/problem+json`.
Health and metrics: `/actuator/health`, `/actuator/prometheus`.

Search the projection (fuzzy on plate, policy, claim number and description; optional status filter):

```bash
curl -s 'localhost:8081/api/v1/search?q=wa12354&status=SUBMITTED' | jq   # note the typo in the plate
```

## Tests

```bash
mvn verify              # all modules: unit + Testcontainers integration tests (needs Docker)
mvn verify -DskipITs    # unit tests only
```

```bash
cd assessment-service && pip install -r requirements-dev.txt && python -m pytest
```

Integration tests spin up real Postgres 16, Kafka (KRaft) and Elasticsearch 8 containers:
Flyway migrations, sequence-backed claim numbering, JPA optimistic locking, the outbox relay
(same-transaction write, rollback, per-claim ordering on one partition), the search
projection (fuzzy search, stale-event rejection) and the BPMN process on the real engine with
the job executor running (approve path, reject path, SLA timer fired by hand via
`ManagementService.executeJob`) are exercised for real, not mocked. The saga is tested from both
sides: claim-service ITs run the BPMN against a fake participant (happy path → `PAID`; payout
failure → `RELEASE_FUNDS` sent, `PAYOUT_FAILED`; reservation rejected → no compensation), and
payout-service ITs prove a redelivered command is handled once, a failed transfer is recorded and
released, and a poison message lands on the DLT and can be replayed. The HTTP triage adapter is
tested with MockWebServer (remote verdict, retry-then-fallback, timeout).

## Design decisions

- **Claim is an aggregate, not a DTO with setters.** Every state change is a behaviour
  method (`approve`, `reject`, …) that enforces the legal transitions defined in
  `ClaimStatus`. The controller and, later, the process engine cannot put a claim into an
  invalid state.
- **Optimistic locking via `@Version`.** Two adjusters acting on the same claim cannot
  both win; the loser gets `409` and reloads. Covered by an integration test.
- **Flyway owns the schema**, Hibernate runs with `ddl-auto: validate` so drift fails fast at startup.
- **Transactional outbox, not `save()` + `kafkaTemplate.send()`.** The dual write can lose an event
  (commit, then broker down) or invent one (send, then commit fails). Instead the event row is written
  in the aggregate's transaction and `OutboxPublisher` relays it to Kafka, locking batches with
  `FOR UPDATE SKIP LOCKED` so multiple instances can poll safely. Delivery is at-least-once, keyed by
  claim id so per-claim order holds. Debezium would remove the polling latency; a poller was chosen
  because it needs no extra infrastructure.
- **Kafka carries immutable business facts** (`CLAIM_SUBMITTED`, `CLAIM_APPROVED`, …) with a full
  claim snapshot, so any number of consumers can build their own view without calling back.
  Work dispatch (notifications, reminders) will go through RabbitMQ in a later phase — different
  problem, different tool.
- **The search projection is idempotent for free.** `search-service` indexes with Elasticsearch
  external versioning set to the outbox sequence number; a redelivered or out-of-order event is
  rejected with a 409 and ignored. Poison messages retry with exponential backoff then land on
  `claims.events.DLT` instead of blocking the partition. This is CQRS: Postgres is the write model,
  Elasticsearch the read model, Kafka the bridge.
- **The process engine orchestrates, the aggregate decides.** `claim-handling.bpmn`
  (embedded Camunda 7, tables in the same Postgres) drives: start assessment → automated triage →
  `Adjuster review` user task → approve/reject. Delegates are thin bridges to `ClaimService`; the
  legal-transition rules stay in the `Claim` aggregate, so a mis-modelled process cannot corrupt a
  claim. The process is started in the submit transaction — no claim without a process instance
  and vice versa — and the first service task is `asyncBefore`, so the HTTP call returns as soon as
  the claim is committed. The application layer sees only a `ClaimWorkflow` port.
- **Non-interrupting SLA timer.** A 48h boundary timer on the review task escalates (flag +
  log now, RabbitMQ notification in phase 5) without cancelling the task. Task due dates are
  set from the same SLA so the console can show time remaining.
- **Triage behind a port with a deterministic default.** `AssessmentProvider` has a heuristic
  adapter (keywords + estimate → MINOR/MODERATE/SEVERE and a banded amount). Tests and the demo need
  no model; phase 4 adds an HTTP adapter to the Python vision service behind a property switch.
- **Saga orchestrated in BPMN with real compensation.** After approval, `Reserve funds` and
  `Issue payout` are executed by `payout-service` over Kafka command/reply (`payout.commands` /
  `payout.events`, keyed by claim id), then the claim is marked paid. Each remote leg is its own
  sub-process with a compensation handler (`Release funds`, `Reverse payout`), so a handler is only
  registered once its leg succeeded. A failed or timed-out leg triggers a compensation throw inside
  the saga scope: completed legs are undone in reverse order, then the claim becomes
  `PAYOUT_FAILED` with the reason. Replies are correlated on business key **and** the pending
  command id, so a late reply from a previous leg cannot be mistaken for the current one.
- **Commands and replies also go through the outbox.** Same table, different topic. Compensation
  commands are fire-and-forget at-least-once; the participant applies them idempotently.
- **The participant can be killed mid-processing and never double-pays.** `payout-service` handles
  each command in one local transaction: `processed_message` row (PK = command id) + ledger change +
  outbox reply. Kafka gives at-least-once; the primary key turns it into effectively-once. Poison
  messages retry with backoff, then park on `payout.commands.DLT`; `POST /api/v1/dlq/replay`
  re-drives them after the fix.
- **Triage service is honest about being small.** `assessment-service` is a FastAPI service with a
  weighted-keyword model plus an amount prior — deterministic, versioned (`modelVersion`), ~50 MB
  container. It sits behind the same `/assess` contract a vision model would. claim-service calls
  it with WebClient (3 s timeout, 2 retries) and degrades to the in-process heuristic, recording
  `heuristic-fallback` as the provider so the degradation is visible.
- **One claim = one trace, across the broker.** Micrometer Tracing (OTel bridge) with W3C
  propagation on HTTP and Kafka. The gap is the outbox: the request trace ends at commit and the
  relay runs later on a scheduler thread. `OutboxWriter` stores the `traceparent` in the row and
  `OutboxPublisher` re-activates it around the send, so consumers join the originating trace
  (`TracePropagationIT` asserts the Kafka header carries the submit's trace id). The Camunda job
  executor is the one hop that starts a fresh trace; business key + claim id in logs bridge it.
- **Metrics that mean something.** `claims_submitted_total`, `claims_transitions_total{to}`,
  `outbox_pending`, `outbox_published_total`, `assessment_requests_total{severity}` next to the
  usual HTTP/Kafka/JVM metrics; the dashboard is provisioned, not clicked together.
- **Logs to Loki, not Elasticsearch.** loki4j appender behind `LOKI_URL`, low-cardinality labels
  (`app`, `level`), trace id in the line for the log↔trace jump. No Kibana.
- **No shared event library.** Each consumer owns its view of the contract and ignores unknown
  fields, so the producer can add fields without a lock-step deploy. A Pact contract test will
  guard the shape in a later phase.
- **Small footprint by default.** JVM flags `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx256m`
  and `lazy-initialization` in the `dev` profile. Compose profiles (`core`, later `search`,
  `observability`, `ml`) mean nobody has to run the whole stack.

## What I deliberately left out, and why

- **MongoDB** — Postgres `jsonb` stores model-extraction output fine; a second datastore was not justified.
- **Camunda 8** — Zeebe + Operate + Tasklist + its own Elasticsearch is 4–6 GB. Camunda 7 embedded
  in the service reuses the existing Postgres at near-zero cost and still gives BPMN, human tasks,
  timers and compensation events. For production I would pick C8 for horizontal scalability of the engine.
- **Paid LLM APIs and heavyweight ML runtimes** — severity estimation is a small deterministic model
  in `assessment-service` behind an `AssessmentProvider` port with an in-process heuristic fallback,
  so the demo works with no model download and tests stay deterministic. A torch/ONNX vision model
  would be a drop-in behind the same `/assess` contract.
- **Kibana** — duplicates Grafana; Loki gives trace-id-to-logs at a fraction of the memory.
- **Spring Cloud Eureka / Config Server** — Kubernetes already does discovery and config.
- **A running Jenkins** — the `Jenkinsfile` is committed; GitHub Actions is the CI that actually runs.

## Layout

```
claim-service/          Spring Boot 3 / Java 21 — the claim aggregate
  src/main/java/com/kmultan/claims/
    domain/             Claim aggregate, ClaimStatus state machine, repository port
    application/        ClaimService — transactional use cases
    api/                REST controller, DTOs, ProblemDetail error mapping
    infrastructure/     Postgres-backed adapters
    infrastructure/outbox/  outbox entity, SKIP LOCKED batch query, Kafka relay
    infrastructure/camunda/ ClaimWorkflow adapter, service-task delegates, task listener
    infrastructure/assessment/  heuristic AssessmentProvider
    application/workflow/   ClaimWorkflow port, ReviewTask, ReviewDecision
  src/main/resources/processes/claim-handling.bpmn
  src/main/resources/db/migration/   Flyway migrations (V1 claim, V2 outbox); Camunda manages its own tables
    infrastructure/payout/  outbox-backed command sender, Kafka reply listener
    application/payout/     PayoutCommand / PayoutReply contract (this service's copy)
payout-service/         Spring Boot 3 — saga participant: ledger, stub payment gateway, idempotent consumer, DLQ replay
  application/          PayoutCommandHandler (one transaction per command), contract records
  domain/               FundReservation, Payout, ProcessedMessage, PaymentGateway port
  infrastructure/       outbox (deliberate copy of claim-service's), Kafka listener + DLT, stub gateway
assessment-service/     FastAPI — POST /assess: weighted-keyword severity model, versioned; pytest
adjuster-console/       Next.js 14 (App Router, plain JS) — task list, claim/unclaim, approve/reject, demo submit
infra/postgres/         init script creating one database per service
infra/observability/    Prometheus scrape config, Loki/Tempo configs, Grafana datasources + dashboard
deploy/helm/            claims-platform chart (services, dev infra, observability); deploy/kind/up.sh
perf/                   k6 ingestion load test
Jenkinsfile             same pipeline as GitHub Actions, for a Jenkins agent with Docker
search-service/         Spring Boot 3 — Kafka consumer -> Elasticsearch projection + search API
  projection/           event envelope (own copy), ClaimDocument, external-versioned indexer, listener
  api/                  GET /api/v1/search (fuzzy multi_match, status filter, paging)
  config/               DLT error handler, index mapping bootstrap, JSON mapper
pom.xml                 aggregator only — each service keeps its own Boot parent
docker-compose.yml      profiles: core, search, console, ml, observability; .env.example toggles trace/log export
.github/workflows/      CI: mvn verify with Testcontainers
```
