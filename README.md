# Motor Insurance Claims Platform

An event-driven claims-handling platform for motor insurance: a policyholder submits a damage claim
with photos, a small ML service triages it, an adjuster decides, and the payout runs as a distributed
saga that compensates itself when a leg fails. Six deployable units, four data stores, one Kafka bus —
and everything runs on a single laptop, for free, with `docker compose up`.

---

## Contents

1. [What the system does](#1-what-the-system-does)
2. [Architecture](#2-architecture)
3. [Design decisions and their trade-offs](#3-design-decisions-and-their-trade-offs)
4. [Domain model and event catalogue](#4-domain-model-and-event-catalogue)
5. [Security model](#5-security-model)
6. [Running it](#6-running-it)
7. [API reference](#8-api-reference)
8. [Testing strategy](#9-testing-strategy)
9. [Performance baselines](#10-performance-baselines)
10. [Observability](#11-observability)
11. [Failure drills you can run](#12-failure-drills-you-can-run)
12. [CI/CD and deployment](#13-cicd-and-deployment)
13. [Known limitations and next steps](#16-known-limitations-and-next-steps)

---

## 1. What the system does

```
 policyholder ──POST claim + photos──▶ claim-service ──CLAIM_SUBMITTED──▶ assessment-service (MobileNetV2 + text model)
                                          ▲                                        │
                                          └──────────── ASSESSMENT_COMPLETED ◀─────┘
                       adjuster ──approve/reject──▶ claim-service ──CLAIM_APPROVED──▶ payout-service
                                          ▲                                        │  reserve funds  → FUNDS_RESERVED
                                          │                                        │  (own event)    → transfer → PAYOUT_ISSUED
                                          │                                        │                    or PAYOUT_FAILED + FUNDS_RELEASED
                                          └──── PAYOUT_ISSUED / PAYOUT_FAILED ◀────┘
                       claim-service: PAID — or PAYOUT_FAILED, which finance can retry (→ CLAIM_APPROVED again)
                       claim withdrawn after approval → PAYOUT_UNACCEPTED → payout-service reverses the transfer
                       search-service projects every claim fact into Elasticsearch (search + event timeline)
```

A claim moves through `SUBMITTED → PENDING_REVIEW → APPROVED → PAID`, with `REJECTED`, `WITHDRAWN` and a
retryable `PAYOUT_FAILED` on the side. Nothing coordinates this centrally: each service reacts to facts
published on Kafka (event choreography), and time-based behaviour — a 48-hour review SLA, a 2-minute
triage timeout with an in-process fallback — is a small scheduler inside claim-service.

## 2. Architecture

| Unit | Stack | Responsibility |
|---|---|---|
| **claim-service** | Java 21, Spring Boot 3.5, Postgres, Redis | The write model: `Claim` aggregate with a state machine and optimistic locking; accounts and JWT issuing; review queue with SLA scheduler; outbox publisher; idempotent consumers of assessment and payout events; admin API |
| **payout-service** | Java 21, Spring Boot 3.5, Postgres (own database) | Saga participant: fund reservations and transfers in a ledger, sync stub or asynchronous webhook-based payment gateway, timeout compensation, dead-letter replay |
| **search-service** | Java 21, Spring Boot 3.5, Elasticsearch | CQRS read side: `claims` index (current state, fuzzy search) and `claim-events` index (append-only fact log, timelines, Kibana) |
| **assessment-service** | Python 3.12, FastAPI, ONNX Runtime | Triage: MobileNetV2 on the photos (zero-shot "wreck vs intact vehicle" signal) + weighted-keyword text model + amount prior; consumes `CLAIM_SUBMITTED`, publishes `ASSESSMENT_COMPLETED` |
| **payment-gateway-simulator** | Java 21, Spring Boot 3.5 (stateless) | Stand-in for a real payment provider: accepts a transfer with `202 ACCEPTED`, confirms or rejects it later over a signed webhook |
| **adjuster-console** | Next.js 14, TypeScript, Recharts | One console, four roles: policyholder, adjuster, finance, admin |
| **platform-commons / platform-outbox** | Java libraries | Infrastructure shared by the Java services: Kafka dead-lettering, W3C trace propagation, JWT resource-server security, CORS, problem details, latency histograms, logging; the outbox (entity, `SKIP LOCKED` relay, trace carrier) |
| Infrastructure | Postgres 16, Kafka 3.8 (KRaft, native image), Redis 7, nginx gateway, Elasticsearch 8 + Kibana, Prometheus, Loki, Tempo, Grafana, Jenkins | All memory-capped in Compose; the whole stack is ~5 GB, the core is ~2 GB |

Each service owns its data. The only things crossing service boundaries are Kafka events, the two
shared infrastructure libraries, and one HTTP call (assessment-service fetches claim photos with a
service-account token).

## 3. Design decisions and their trade-offs

The full, immutable records live in [docs/adr/](docs/adr/README.md) — one numbered ADR per
decision, with context and consequences. The highlights:

These are the conversations the code is built to support. Each one names the alternative and when it
would be the better choice.

**Transactional outbox instead of `save()` then `kafkaTemplate.send()`.**
The dual write can lose an event (commit, then the broker is down) or invent one (send, then the commit
fails). `OutboxWriter` appends the event in the aggregate's transaction; `OutboxPublisher` relays batches
locked with `FOR UPDATE SKIP LOCKED`, so several instances can poll safely. Delivery is at-least-once and
keyed by claim id, so per-claim order holds. *Alternative:* Debezium reading the WAL removes polling
latency (~1 s here) at the cost of a Kafka Connect deployment. `OutboxResilienceIT` pauses the Kafka
container mid-run and proves nothing is lost.

**Saga with real compensation, no coordinator.**
`CLAIM_APPROVED` → payout-service reserves funds (`FUNDS_RESERVED`) → payout-service reacts to its *own*
fact and transfers (`PAYOUT_ISSUED`, or `PAYOUT_FAILED` + `FUNDS_RELEASED` as local compensation) →
claim-service marks `PAID`. If the claim can no longer accept money (withdrawn after approval),
claim-service publishes `PAYOUT_UNACCEPTED` and payout-service reverses the transfer — cross-service
compensation driven purely by events. `PAYOUT_FAILED` is retryable with a corrected amount.

**Idempotent consumers: at-least-once turned into effectively-once.**
Every reaction is one local transaction containing a `processed_message` row (primary key = event id),
the state change, and the outbox rows it produces. A consumer killed between commit and offset commit
sees the event again and skips it. `PayoutSagaIT` delivers every approval twice and asserts exactly one
payout; `PayoutThroughputIT` does it 200 times under load. Poison messages retry with exponential backoff,
then park on `<topic>.DLT`; `POST /api/v1/dlq/replay` re-drives them after the fix.

**The aggregate decides, the message handler does not.**
`ClaimStatus.allowedTransitions()` is the single authority on legal transitions. Late or duplicated
events (an assessment for a withdrawn claim, a second `PAYOUT_ISSUED` for a paid claim) are explicitly
ignored by the aggregate's state checks, so a mis-sequenced event cannot corrupt a claim.

**CQRS read model with idempotency for free.**
search-service indexes each claim into Elasticsearch using *external versioning* set to the outbox
sequence number; a redelivered or out-of-order event is rejected by ES with a 409 and ignored — no
bookkeeping table. The same events feed an append-only `claim-events` index (one document per fact),
which powers timelines in the console and analytics in Kibana.

**Kafka for facts; no RabbitMQ.**
Kafka carries immutable business facts that several consumers replay independently. The original plan
also had RabbitMQ for work dispatch (notifications with retry/backoff); it was dropped because nothing in
scope needed it — adding a broker without a workload is a negative signal.

**Redis where a relational database is the wrong tool.**
Two guards sit in front of claim submission: an `Idempotency-Key` (a mobile client that times out and
retries must not create two claims; `SET NX EX` → in-progress marker → key→claim id for 24 h; a replay
returns the original claim with `Idempotent-Replayed: true`) and a fixed-window rate limit per client
(`INCR`/`EXPIRE`, shared across instances, `429` + `Retry-After`). Both are cheap, expiring,
pre-transaction checks. `RedisGuardsIT` fires 40 concurrent requests and asserts exactly 5 pass.

**Triage that is honest about its model.**
ImageNet MobileNetV2 on ONNX Runtime (14 MB, ~15 ms per image on CPU) read zero-shot: the probability
mass on *wreck* versus intact-vehicle classes is the image damage signal, combined with a weighted-keyword
text model and the estimate. Deterministic, versioned, and the explanation is persisted on the claim so a
reviewer sees *why*. There is no free labelled car-damage dataset in the repo, so the network is not
fine-tuned — a trained head is a change to `vision.py` only. If the service is down, claims still
progress: after 2 minutes the in-process heuristic completes triage, labelled `heuristic-fallback`.

**Consumer-driven contract instead of a shared DTO library.**
Each consumer keeps its own copy of the event envelope and ignores unknown fields, so the producer can add
fields without a lock-step deploy. payout-service pins the fields it reads from `CLAIM_APPROVED` in a
Pact message contract (`contracts/pacts`); claim-service's build verifies its real serialiser against it.
Infrastructure that is genuinely identical, by contrast, lives in `platform-commons`/`platform-outbox`.

**One claim = one trace, across the broker.**
Micrometer Tracing with W3C propagation on HTTP and Kafka. The gap is the outbox: the request trace ends
at commit and the relay runs later on a scheduler thread. The `traceparent` is stored in the outbox row
and re-activated around the send, so every consumer — including the Python service — joins the trace
that submitted the claim. `TracePropagationIT` asserts the Kafka header carries the submit's trace id.

## 4. Domain model and event catalogue

**Claim state machine** (`ClaimStatus`):

```
SUBMITTED ──ASSESSMENT_COMPLETED──▶ PENDING_REVIEW ──approve──▶ APPROVED ──PAYOUT_ISSUED──▶ PAID
                                          │                        │  ▲
                                          └──reject──▶ REJECTED    │  └──retry-payout── PAYOUT_FAILED ◀──PAYOUT_FAILED / RESERVATION_REJECTED──┘
any non-terminal ──withdraw──▶ WITHDRAWN
```

**Topics** (all keyed by claim id → per-claim ordering on one partition; 3 partitions each):

| Topic | Producer | Events |
|---|---|---|
| `claims.events` | claim-service (outbox) | `CLAIM_SUBMITTED`, `ASSESSMENT_COMPLETED`, `REVIEW_CLAIMED`, `REVIEW_UNCLAIMED`, `REVIEW_SLA_BREACHED`, `CLAIM_APPROVED`, `CLAIM_REJECTED`, `CLAIM_PAID`, `PAYOUT_FAILED`, `PAYOUT_UNACCEPTED`, `CLAIM_WITHDRAWN` — each with a full claim snapshot |
| `assessment.events` | assessment-service | `ASSESSMENT_COMPLETED` (severity, amount, provider, model version, score, explanation) |
| `payout.events` | payout-service (outbox) | `FUNDS_RESERVED`, `RESERVATION_REJECTED`, `PAYOUT_ISSUED`, `PAYOUT_FAILED`, `FUNDS_RELEASED`, `PAYOUT_REVERSED` |
| `*.DLT` | Spring Kafka error handler | poison records after 4 attempts, same partition |

Every record carries `eventId`, `eventType`, `sequence` (global outbox order) and `traceparent` headers.
Events carry snapshots, not ids-to-look-up, so consumers never call back into the producer to build
their view.

**Data ownership:** claim-service (`claim`, `claim_photo`, `user_account`, `processed_message`,
`outbox_event`), payout-service (`fund_reservation`, `payout`, `processed_message`, `outbox_event`),
search-service (`claims`, `claim-events` indices), Redis (idempotency keys, rate-limit windows). Schemas
are Flyway-managed (`V1` … `V7`); Hibernate runs with `ddl-auto: validate` so drift fails at startup.

## 5. Security model

| Role | Can |
|---|---|
| `POLICYHOLDER` | submit claims (JSON or multipart with photos); read, list and withdraw **own** claims only |
| `ADJUSTER` | read all claims; review queue: claim/unclaim, approve/reject what they hold; withdraw; search; timelines |
| `FINANCE` | read all claims; retry failed payouts; payout ledger; dead-letter replay; search |
| `ADMIN` | everything, plus statistics, live usage, account management (cannot lock themselves out) |
| `SERVICE` | machine accounts — assessment-service signs in to read photos |

Anonymous → `401`, wrong role or someone else's claim → `403`, both as `application/problem+json`.
Tokens: HS256, 8 h, claims `preferred_username`, `roles`, `name`; secret from `AUTH_JWT_SECRET`
(≥ 32 bytes), shared by the three Java services. Demo accounts are seeded on a fresh database (password =
username): `anna`, `marek` (policyholders), `alice`, `bob` (adjusters), `finance`, `admin`. `SecurityIT`
covers login, expiry, forged and payload-tampered tokens, ownership and review-holder rules with real
tokens on the real filter chain.

## 6. Running it

Requirements: Docker (Compose v2), Java 21 and Maven 3.8+ for the tests, Node 18 for the console.

```bash
cp .env.example .env                                              # secrets, trace/log export toggles, DOCKER_GID
docker compose --profile core up -d --build                       # Postgres, Kafka, Redis, claim/payout/assessment
docker compose --profile core --profile console up -d --build     # + console on http://localhost:3000
docker compose --profile core --profile search up -d --build      # + Elasticsearch, search-service, Kibana
docker compose --profile core --profile observability up -d       # + Prometheus, Loki, Tempo, Grafana
docker compose --profile ci up -d --build                         # + Jenkins
```

| Address | What | Profile |
|---|---|---|
| `localhost:3000` | console (login: see roles above) | `console` |
| `localhost:8080` | claim-service API (through the nginx gateway), `/actuator/health`, `/actuator/prometheus`, `/swagger-ui.html` | `core` |
| `localhost:8082` | payout-service API (through the nginx gateway), `/swagger-ui.html` | `core` |
| `localhost:8083` | payment-gateway-simulator (`POST /transfers`) | `core` |
| `localhost:8000` | assessment-service (`/assess`, `/health`, `/metrics`) | `core` |
| `localhost:8081` | search-service API, `/swagger-ui.html` | `search` |
| `localhost:5601` | Kibana with data views `claims` and `claim-events` | `search` |
| `localhost:3001` | Grafana (dashboard *Claims platform*, Explore → Tempo / Loki) | `observability` |
| `localhost:8090` | Jenkins (`admin`/`admin`), job `claims-platform` | `ci` |

First start builds the images and downloads the ONNX model (a few minutes). Profiles keep the footprint
small: `core` alone is enough to demo the saga. Windows/WSL note: host port 4318 is in a reserved range,
so Tempo's OTLP port is published as `14318`; containers use `tempo:4318` internally.

### Scaling out live

```bash
docker compose --profile core up -d --scale claim-service=3 --scale payout-service=2
docker compose --profile core up -d --scale claim-service=1 --scale payout-service=1   # back down
```

Why this works with no code path caring which replica handles what:

- **Ingress** — claim-service and payout-service publish no host ports; an nginx gateway owns
  `8080`/`8082` and resolves the service name through Docker's DNS, which returns every replica's
  address round-robin. Adding a replica changes nothing but the DNS answer.
- **HTTP is stateless** — auth is a self-contained JWT verified with a shared secret, idempotency keys
  and rate-limit counters live in Redis, business state in Postgres with optimistic locking — so any
  replica can serve any request, including the payment provider's webhook.
- **Kafka partitions are the unit of parallelism** — every topic is keyed by claim id over 3 partitions;
  a consumer group spreads partitions across replicas, so events for one claim stay ordered on one
  consumer while different claims process in parallel. A third payout replica would idle — that is the
  partition count, not a bug.
- **The outbox relay is concurrency-safe** — each tick claims rows with `FOR UPDATE SKIP LOCKED`, so two
  relays never pick the same row and ordering per aggregate is preserved.
- **Schedulers elect a leader** — the SLA/timeout sweeps run on every replica's clock but ShedLock
  (a row lock in Postgres, V8) lets exactly one instance execute a given tick; the sweeps are also
  idempotent, so a lock expiry mid-run cannot double-escalate.

The drill: scale to 3, run `perf/run.sh`, watch per-instance request rates in Grafana, `docker kill`
one replica mid-load — traffic shifts to the survivors, the killed instance's partitions rebalance,
nothing is lost.

## 7. API reference

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
        -d '{"username":"anna","password":"anna"}' | jq -r .accessToken)
curl -s -X POST localhost:8080/api/v1/claims -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'X-Client-Id: demo' \
  -F 'claim={"policyNumber":"POL-123","plateNumber":"WA 12345","incidentDate":"2026-08-20","description":"Front end crushed, airbags deployed","estimatedAmount":9000};type=application/json' \
  -F photos=@front.jpg | jq
```

| Endpoint | Who | What |
|---|---|---|
| `POST /api/v1/auth/login`, `GET /api/v1/auth/me` | anyone / any user | issue a token; who am I |
| `POST /api/v1/claims` (JSON or multipart) | POLICYHOLDER | submit; `Idempotency-Key` replay-safe; rate-limited per `X-Client-Id` |
| `GET /api/v1/claims`, `/{id}`, `/{id}/photos/{photoId}` | owner or staff | list (own for policyholders, `?status=` for staff), detail, photo bytes |
| `POST /api/v1/claims/{id}/withdraw` | owner, ADJUSTER, ADMIN | withdraw while not terminal |
| `POST /api/v1/claims/{id}/retry-payout` | FINANCE, ADMIN | `PAYOUT_FAILED → APPROVED`, optional corrected amount |
| `GET /api/v1/reviews?scope=&severity=&escalatedOnly=&page=&size=`, `/summary` | ADJUSTER, ADMIN | paged queue by SLA due date; counters |
| `POST /api/v1/reviews/{id}/claim|unclaim|approve|reject` | ADJUSTER, ADMIN | assignee = caller; only the holder decides |
| `GET search-service/api/v1/search?q=&status=` | staff | fuzzy search over the projection |
| `GET search-service/api/v1/claims/{id}/events` | staff | event timeline |
| `GET payout-service/api/v1/payouts`, `/{claimId}` | FINANCE, ADMIN | ledger |
| `POST payout-service/api/v1/dlq/replay?topic=` | FINANCE, ADMIN | re-drive a dead-letter topic |
| `GET /api/v1/admin/statistics?days=`, `/usage`, `GET/POST/PATCH /api/v1/admin/users` | ADMIN | dashboard numbers, live meters, accounts |

Errors are RFC 9457 problem details: `400` validation (with an `errors` list), `401`, `403`, `404`,
`409` illegal transition / review held by someone else / optimistic-lock conflict / request in
progress, `422` business rule, `429` with `Retry-After`.

## 8. Testing strategy

```bash
mvn verify              # every module: unit tests + Testcontainers ITs + Spotless + Checkstyle (needs Docker)
mvn verify -DskipITs    # unit tests only
mvn verify -Dperf       # additionally the performance ITs (tag `perf`)
cd assessment-service && pip install -r requirements-dev.txt && python -m pytest
cd adjuster-console && npm ci && npm run typecheck && npm run build
```

The pyramid is deliberately heavy in the middle: domain rules have unit tests (`ClaimTest`,
`HeuristicAssessmentProviderTest`), use cases have Mockito tests, and everything that touches
infrastructure runs against **real Postgres, Kafka, Redis and Elasticsearch in Testcontainers** — mocked
integration tests are the ones that lie.

| Test | Proves |
|---|---|
| `ChoreographyIT` | the whole lifecycle over a real broker with in-JVM fakes of the two downstream services: happy path to `PAID`; `.99` → `PAYOUT_FAILED` → retry → `PAID`; reservation rejected; withdraw-after-approve → `PAYOUT_UNACCEPTED`; SLA escalation once per claim; triage timeout → heuristic fallback |
| `OutboxIT`, `OutboxResilienceIT`, `TracePropagationIT` | same-transaction write, rollback, per-claim ordering on one partition; Kafka paused mid-run → nothing lost, relayed in order when it returns; the Kafka header carries the originating trace id |
| `AsynchronousGatewayIT` | the async provider path: transfer handed off, payout `PENDING` with the reservation held; webhook completion settles, rejection compensates, a redelivered webhook is a no-op, a missing shared token is `401`, an unconfirmed transfer is failed by the timeout sweep |
| `ToxiproxyResilienceIT` | the app talks to Kafka through a Toxiproxy TCP proxy: 1.5 s broker latency never slows the HTTP path (the outbox absorbs it); a severed connection loses nothing and the backlog drains on reconnect |
| `PayoutSagaIT`, `PayoutThroughputIT` | reserve → transfer settles; redelivered approval handled once; failed transfer compensated and retryable; unaccepted payout reversed; poison → DLT → replay; 200 approvals delivered twice → exactly 200 payouts |
| `ClaimProjectionIT`, `SearchPerformanceIT` | fuzzy search, stale-event rejection, event log, timeline endpoint; 300 events → searchable, query p95 |
| `SecurityIT`, `RedisGuardsIT`, `ReviewQueueIT`, `AdminApiIT`, `ClaimControllerIT` | roles, ownership, token attacks; idempotent replay, atomic rate limit under a 40-request burst, window rollover with an injected `Clock`, 10 concurrent requests with one key → one claim; paging/filters; admin statistics and account rules; multipart photos, problem details, optimistic locking |
| `ClaimApprovedContractTest` / `ClaimEventsContractTest` | the Pact message contract, consumer-written and provider-verified |

Shared fixtures come from `platform-commons`' test-jar: `TestJwtTokenFactory` mints tokens exactly as
claim-service does; `KafkaTestConsumer` accumulates records per key for `await()` assertions. Tests use
the same deterministic rules as the real stub gateway: amounts ending in `.99` fail at the provider,
amounts over 50 000 cannot be reserved, descriptions containing `NOASSESS` get no triage.

### Quality gates in every build

`mvn verify` fails on any of: unformatted code (Spotless/Palantir), a Checkstyle violation,
an [Error Prone](https://errorprone.info) bug pattern at compile time, a broken architecture
rule (`ArchitectureTest` — ArchUnit: domain depends on nothing above it, application never
touches adapters, no package cycles, controllers only in `api`, `@Configuration` naming),
line coverage below the JaCoCo gate (claim 85 %, payout 85 %, search 80 % — the platform
libraries are exercised through the services' integration tests), or a Maven Enforcer rule
(JDK 21+, Maven 3.8.1+, no duplicate dependency declarations). CI additionally runs a
Trivy scan (CRITICAL vulnerabilities with a published fix fail the build; the HIGH report is
uploaded for triage), publishes a CycloneDX SBOM, and Dependabot keeps Maven/npm/pip/Docker
/Actions dependencies fresh. Each service publishes its OpenAPI contract at `/v3/api-docs`
(Swagger UI at `/swagger-ui.html`), verified unauthenticated by `OpenApiDocumentationIT`.

## 9. Performance baselines

One laptop under Docker Desktop/WSL2, August 2026 — a baseline to compare before/after a change, not a
capacity claim.

| Scenario | Result |
|---|---|
| `POST /api/v1/claims`, 20 threads × 25, MockMvc on real Postgres + Redis (`ClaimServicePerformanceIT`) | p50 49 ms · p95 146 ms · 273 req/s |
| Outbox relay after that burst (poll 1 s, batch 100) | 500+ events drained in ~10 s |
| 50 claims submitted → `PENDING_REVIEW` (two Kafka hops each) | 1.2 s |
| payout-service, 200 approvals each delivered twice | exactly 200 payouts, 5.4 s |
| search-service, fuzzy query (100 runs) | p50 8 ms · p95 17 ms |
| **k6 ingest** against the running stack, 20 VUs, 45 s (`perf/run.sh`) | 5 947 submissions, 130 req/s, p95 97 ms, 0 failures |
| **k6 full lifecycle** (`perf/k6-lifecycle.js`): submit → triage → take → approve (1 in 6 fails at the provider) → retry | 189 cycles in 120 s with 8 VUs, 100 % checks |

The per-client rate limit dominates any load test, so raise it for the run:
`RATE_LIMIT_SUBMIT_PER_MINUTE=1000000 docker compose --profile core up -d claim-service`.

## 10. Observability

- **Metrics** — Micrometer → Prometheus. Business counters next to the platform ones:
  `claims_submitted_total`, `claims_transitions_total{to}`, `outbox_pending`, `outbox_published_total`,
  `assessment_requests_total{severity}`, `assessment_latency_seconds`. Latency histograms for HTTP and
  Kafka listeners are enabled by a `MeterFilter` in `platform-commons` (the p95 panels need buckets).
  The Grafana dashboard is provisioned, not clicked together.
- **Traces** — OpenTelemetry over OTLP to Tempo, sampled at 100 % for the demo. One trace per claim
  across claim-service → Kafka → assessment-service (Python) / payout-service / search-service.
- **Logs** — loki4j appender behind `LOKI_URL`, low-cardinality labels (`app`, `level`), trace id in the
  line; Grafana links a log line to its trace and a span to its logs.
- **Alerts** — provisioned in Grafana (`infra/observability/grafana/provisioning/alerting/`): service
  down, outbox backlog above 100 for 5 min (broker unreachable), Kafka listener failures (records heading
  for the DLT), HTTP 5xx ratio above 5 %, JVM heap above 90 %. Each rule carries a runbook annotation;
  delivery goes to a webhook contact point you can repoint at Slack or Opsgenie.
- **Kibana** is for business facts, not logs: Discover over `claim-events` answers "what happened to
  CLM-2026-000042" and "approvals per hour by severity".

## 11. Failure drills you can run

- **Payment provider rejects**: approve any amount ending in `.99` → `PAYOUT_FAILED` with the reason,
  reservation released, finance retries with a corrected amount.
- **Reservation limit**: approve more than 50 000 → `RESERVATION_REJECTED`, nothing to compensate.
- **Withdraw after approval**: withdraw while the payout is in flight → `PAYOUT_UNACCEPTED` → payout-service
  reverses the transfer; the ledger shows `REVERSED`.
- **Provider never confirms**: approve an amount ending in `.77` → the gateway accepts the transfer and
  goes silent; the payout sits in `PENDING` until the timeout sweep fails it, releases the reservation
  and publishes `PAYOUT_FAILED` — finance retries as usual.
- **Payment gateway down**: `docker compose stop payment-gateway` → approvals park as `PENDING`
  reservations-held payouts; the timeout sweep compensates them; start it again and new approvals flow.
- **ML service down**: `docker compose stop assessment-service` → claims still reach review after 2
  minutes, labelled `heuristic-fallback`.
- **Broker down**: `docker pause claims-kafka` → submissions keep succeeding, `outbox_pending` climbs on the
  dashboard; `docker unpause` → everything is relayed in order.
- **Consumer killed mid-processing**: `docker kill payout-service` during the lifecycle load → on restart
  the redelivered events are skipped by `processed_message`; no double payout.
- **Poison message**: produce garbage to `claims.events` → it lands on `claims.events.DLT` after 4
  attempts; replay from the admin panel.
- **Client retry**: repeat a `POST /api/v1/claims` with the same `Idempotency-Key` → `200` pointing at the
  original claim, not a second one.

## 12. CI/CD and deployment

- **GitHub Actions** (`.github/workflows/ci.yml`): `mvn verify` with Testcontainers, pytest, console
  build, `helm lint`/`template`; a `workflow_dispatch` input adds the performance ITs.
- **Jenkins** (`infra/jenkins`, profile `ci`): the same pipeline for real — an image with Maven, Node,
  Python, Helm and the Docker CLI, Configuration-as-Code with a seed job, the repository mounted and built
  on the built-in node, Testcontainers through the host socket (`DOCKER_GID` in `.env`). Parameters
  `PERF` and `BUILD_IMAGES`.
- **Docker images** build from the repository root (`docker build -f claim-service/Dockerfile .`) because
  the services depend on the library modules; Compose, Jenkins and the kind script do this.
- **Kubernetes**: `deploy/helm/claims-platform` deploys the five services plus single-replica,
  `emptyDir`-backed infrastructure and the observability stack — a demo cluster, explicitly not how the
  infrastructure would be run in production (that is what CloudNativePG, Strimzi and ECK are for).
  `./deploy/kind/up.sh` builds, loads and installs.


## 13. Known limitations and next steps

- HS256 with a shared secret means every verifying service holds the signing key; RS256 with a JWKS
  endpoint is the upgrade if a third party ever verifies tokens.
- The claim-service scheduler runs in every instance; with several replicas the SLA sweep needs a
  lock (`SKIP LOCKED` on the claim rows, or ShedLock).
- assessment-service has no database and therefore no outbox: it commits the Kafka offset after the
  produce is flushed (at-least-once, deduplicated by the consumer).
- The vision model is not fine-tuned; the zero-shot signal is real but weak on synthetic images.
- Photos in `bytea` are fine for a demo; a `StorageAdapter` to S3/MinIO is the obvious next step.
- A second Pact contract (assessment-service ⇄ claim-service) and a token-bucket rate limiter would be
  the next two small additions.
