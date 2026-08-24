# Motor Insurance Claims Platform

A claims-handling platform for motor insurance, built as a small set of services that
demonstrate the patterns that actually matter in distributed systems: a transactional
outbox, a saga with compensation, idempotent consumers, and end-to-end tracing —
all runnable on one laptop for free.

[![CI](https://github.com/kmultan/insurance-claims-project/actions/workflows/ci.yml/badge.svg)](https://github.com/kmultan/insurance-claims-project/actions/workflows/ci.yml)

## The flow

A policyholder uploads photos and a description of the damage. The system ingests the
claim; a Python service reacts to the event, runs MobileNet on the photos plus a text model
and publishes a severity; the claim lands in an adjuster's review queue with an SLA; approval
is a fact that the payout service reacts to — reserve funds, pay, and compensate if any step
fails — and the claim ends up `PAID` (or `PAYOUT_FAILED`, retryable). There is no central
orchestrator: every step is a service reacting to a published fact (event choreography).

```
 client ──POST claim+photos──▶ claim-service ──CLAIM_SUBMITTED──▶ assessment-service (MobileNet + text)
                                    ▲                                        │
                                    └────────── ASSESSMENT_COMPLETED ◀───────┘
                              adjuster ──approve──▶ claim-service ──CLAIM_APPROVED──▶ payout-service
                                    ▲                                        │  reserve → FUNDS_RESERVED
                                    │                                        │  (self)  → PAYOUT_ISSUED | PAYOUT_FAILED+FUNDS_RELEASED
                                    └── PAYOUT_ISSUED / PAYOUT_FAILED ◀──────┘
                              claim-service: PAID, or PAYOUT_FAILED (retry-payout → CLAIM_APPROVED again)
                              claim withdrawn after approval → PAYOUT_UNACCEPTED → payout-service reverses
                              search-service projects every claims.event into Elasticsearch
```

## Status: all phases done; v2 = event choreography, MobileNet triage, retryable payouts, TS console, Pact

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | `claim-service`: claim aggregate, Postgres, REST + validation, Testcontainers, Compose | **done** |
| 2 | Kafka + transactional outbox + Elasticsearch read projection (`search-service`) | **done** |
| 3 | Review queue with SLA escalation, TypeScript Next.js console | **done** (originally Camunda 7; replaced by choreography) |
| 4 | Choreographed payout saga with compensation, `payout-service` (idempotent, DLQ replay), `assessment-service` (Kafka + MobileNet), Pact contract | **done** |
| 5 | Prometheus/Grafana/Loki/Tempo, trace context through outbox + Kafka headers, business metrics, Helm chart + kind script, Jenkinsfile, k6 | **done** |
| 6 | Optional: drag-and-drop upload page, WebFlux document streaming | planned |

## Run it

Requirements: Java 21, Maven 3.8+, Docker.

```bash
# Postgres + Kafka + claim-service + payout-service + assessment-service
docker compose --profile core up -d --build

# add Elasticsearch + search-service
docker compose --profile core --profile search up -d --build

# add the adjuster console (http://localhost:3000)
docker compose --profile core --profile console up -d --build

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
approve an amount ending in `.99` (payment provider rejects → reservation is released, claim
`PAYOUT_FAILED`, retry from the console) or above `50000` (reservation rejected). Watch it:

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

Submit a claim (JSON, or multipart with photos):

```bash
curl -s -X POST localhost:8080/api/v1/claims -H 'Content-Type: application/json' -d '{
  "policyNumber": "POL-123", "plateNumber": "WA 12345", "incidentDate": "2026-08-20",
  "description": "Rear-ended at a red light, bumper and tail light damaged", "estimatedAmount": 2500.00 }' | jq

curl -s -X POST localhost:8080/api/v1/claims \
  -F 'claim={"policyNumber":"POL-123","plateNumber":"WA 12345","incidentDate":"2026-08-20","description":"Front end crushed, airbags deployed","estimatedAmount":9000};type=application/json' \
  -F photos=@front.jpg -F photos=@side.jpg | jq
```

assessment-service reacts to `CLAIM_SUBMITTED`, fetches the photos, runs MobileNet + the text model
and publishes `ASSESSMENT_COMPLETED`; the claim then shows up in the review queue:

```bash
curl -s localhost:8080/api/v1/reviews | jq                       # PENDING_REVIEW claims, severity, due date, photos
curl -s -X POST localhost:8080/api/v1/reviews/$ID/claim -H 'Content-Type: application/json' -d '{"assignee":"alice"}'
curl -s -X POST localhost:8080/api/v1/reviews/$ID/approve -H 'Content-Type: application/json' -d '{"approvedAmount":2000}'
curl -s -X POST localhost:8080/api/v1/reviews/$ID/reject  -H 'Content-Type: application/json' -d '{"reason":"..."}'
curl -s -X POST localhost:8080/api/v1/claims/$ID/retry-payout -H 'Content-Type: application/json' -d '{"approvedAmount":2001}'
```

Illegal transitions return `409` as RFC 9457 `application/problem+json`.
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
Flyway migrations, sequence-backed claim numbering, JPA optimistic locking, multipart photo
upload, the outbox relay (same-transaction write, rollback, per-claim ordering on one partition),
the search projection (fuzzy search, stale-event rejection) and the whole choreography over the
real broker with in-JVM fakes of the two downstream services (`ChoreographyIT`: happy path to
`PAID`; `.99` → `PAYOUT_FAILED` → retry with corrected amount → `PAID`; reservation rejected;
withdraw-after-approve → `PAYOUT_UNACCEPTED`; SLA escalation once per claim; triage timeout →
heuristic fallback). payout-service ITs prove a redelivered event is handled once, a failed transfer
is compensated and can be retried, an unaccepted payout is reversed, and a poison message lands on
the DLT and can be replayed. A **Pact message contract** (`contracts/pacts`) is written by
payout-service's consumer test and verified against claim-service's real serialiser.

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
- **Choreography, not orchestration.** No process engine: each service reacts to facts on Kafka
  (`claims.events`, `assessment.events`, `payout.events`, all keyed by claim id). The claim
  aggregate still owns the legal transitions, so a mis-sequenced event cannot corrupt a claim —
  late or duplicate results are ignored explicitly (`processed_message` inbox + status checks).
  Time-based behaviour a BPMN engine used to own is a small scheduler: review-SLA escalation
  (a non-blocking fact, once per claim) and a triage timeout that applies the in-process heuristic
  when assessment-service does not answer. The trade-off is visibility: there is no diagram of "where
  the claim is"; the answer is the claim's status plus the event log in Elasticsearch/Kafka.
- **Saga as a chain of reactions with compensation.** `CLAIM_APPROVED` → payout-service reserves
  funds (`FUNDS_RESERVED`) → payout-service reacts to its *own* fact and transfers (`PAYOUT_ISSUED`, or
  `PAYOUT_FAILED` + `FUNDS_RELEASED` as local compensation) → claim-service marks `PAID`. If the
  claim can no longer take the money (withdrawn after approval) it publishes `PAYOUT_UNACCEPTED` and
  payout-service reverses — cross-service compensation without an orchestrator. `PAYOUT_FAILED` is
  retryable: `retry-payout` moves the claim back to `APPROVED` and republishes the fact.
- **Consumer-driven contract.** payout-service pins only the fields it reads from `CLAIM_APPROVED`
  in a Pact message contract; claim-service's build verifies its serialiser against it. Consumers
  keep their own copies of the envelope and ignore unknown fields — no shared library.
- **Triage that is honest about its model.** assessment-service runs ImageNet MobileNetV2 (ONNX
  Runtime, CPU, ~14 MB) on the photos and reads the head zero-shot: probability mass on *wreck*
  versus intact-vehicle classes is the image damage signal, combined with a weighted-keyword text
  model and the estimate. Deterministic and versioned; the explanation is in the event. There is no
  free labelled car-damage dataset shipped here, so the network is not fine-tuned — a trained head is
  a change to `vision.py` only. If the service is down, claims still progress via the fallback.
- **No shared event library.** Each consumer owns its view of the contract and ignores unknown
  fields, so the producer can add fields without a lock-step deploy. A Pact contract test will
  guard the shape in a later phase.
- **Small footprint by default.** JVM flags `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx256m`
  and `lazy-initialization` in the `dev` profile. Compose profiles (`core`, later `search`,
  `observability`, `ml`) mean nobody has to run the whole stack.

## What I deliberately left out, and why

- **MongoDB** — Postgres `jsonb` stores model-extraction output fine; a second datastore was not justified.
- **Paid LLM APIs and heavyweight ML runtimes** — triage is ImageNet MobileNetV2 on ONNX Runtime
  (no torch, ~15 ms per image on CPU) plus a text model; deterministic, so tests stay deterministic.
- **A process engine (Camunda)** — the first version used embedded Camunda 7 for the review task and
  the saga; it was replaced by event choreography to remove the orchestrator as a single point of
  coupling. Camunda remains the right answer when business users must own the process diagram.
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
    infrastructure/consumers/  Kafka reactions to assessment.events / payout.events (idempotent)
    infrastructure/assessment/ heuristic fallback AssessmentProvider
    application/            ClaimService (use cases), ClaimScheduler (SLA escalation, triage timeout), IdempotentConsumer
  src/main/resources/db/migration/   Flyway migrations (V1 claim … V5 choreography: review/triage columns, photos, inbox)
payout-service/         Spring Boot 3 — saga participant: ledger, stub payment gateway, idempotent consumer, DLQ replay
  application/          PayoutSaga (one transaction per reaction), own copies of the event envelopes
  domain/               FundReservation, Payout, ProcessedMessage, PaymentGateway port
  infrastructure/       outbox (deliberate copy of claim-service's), Kafka listener + DLT, stub gateway
assessment-service/     FastAPI + Kafka consumer — MobileNetV2 (ONNX) + text model; POST /assess for ad-hoc use; pytest
contracts/pacts/        Pact message contract payout-service ⇄ claim-service (consumer-written, provider-verified)
adjuster-console/       Next.js 14 + TypeScript — review queue with photos, claim/unclaim, approve/reject, failed-payout retry, demo submit with photos
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
docker-compose.yml      profiles: core (Postgres, Kafka, claim/payout/assessment), search, console, observability
.github/workflows/      CI: mvn verify with Testcontainers
```
