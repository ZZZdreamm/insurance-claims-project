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

# add the console (http://localhost:3000): log in as anna / alice / finance / admin (password = username)
docker compose --profile core --profile console up -d --build

# search profile also brings Kibana (http://localhost:5601) with data views over the claims indices
docker compose --profile core --profile search up -d --build

# a real Jenkins (http://localhost:8090, admin/admin) with the pipeline job already configured
docker compose --profile ci up -d --build

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

Every `/api/**` call needs a bearer token. claim-service issues them itself (HS256, secret shared
with the other services via `AUTH_JWT_SECRET`); demo accounts are seeded on a fresh database
(password = username): `anna`, `marek` (policyholders), `alice`, `bob` (adjusters), `finance`, `admin`.

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
        -d '{"username":"anna","password":"anna"}' | jq -r .accessToken)
```

Submit a claim as a policyholder (JSON, or multipart with photos). `Idempotency-Key` makes a client
retry safe; submissions are rate-limited per client (`X-Client-Id`, else IP) — `429` + `Retry-After`:

```bash
curl -s -X POST localhost:8080/api/v1/claims -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -H 'X-Client-Id: demo' -d '{
  "policyNumber": "POL-123", "plateNumber": "WA 12345", "incidentDate": "2026-08-20",
  "description": "Rear-ended at a red light, bumper and tail light damaged", "estimatedAmount": 2500.00 }' | jq

curl -s -X POST localhost:8080/api/v1/claims -H "Authorization: Bearer $TOKEN" \
  -F 'claim={"policyNumber":"POL-123","plateNumber":"WA 12345","incidentDate":"2026-08-20","description":"Front end crushed, airbags deployed","estimatedAmount":9000};type=application/json' \
  -F photos=@front.jpg -F photos=@side.jpg | jq
```

assessment-service reacts to `CLAIM_SUBMITTED`, fetches the photos, runs MobileNet + the text model
and publishes `ASSESSMENT_COMPLETED`; the claim then shows up in the review queue:

```bash
# as alice (ADJUSTER): the assignee is always the caller; only the holder can decide
curl -s localhost:8080/api/v1/reviews -H "Authorization: Bearer $ALICE" | jq
curl -s -X POST localhost:8080/api/v1/reviews/$ID/claim   -H "Authorization: Bearer $ALICE"
curl -s -X POST localhost:8080/api/v1/reviews/$ID/approve -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"approvedAmount":2000}'
# as finance (FINANCE): retry a failed payout
curl -s -X POST localhost:8080/api/v1/claims/$ID/retry-payout -H "Authorization: Bearer $FINANCE" -H 'Content-Type: application/json' -d '{"approvedAmount":2001}'
```

| Role | Can |
|---|---|
| `POLICYHOLDER` | submit claims; read, list and withdraw **own** claims only |
| `ADJUSTER` | read all claims; review queue: claim/unclaim, approve/reject what they hold; withdraw |
| `FINANCE` | read all claims; retry failed payouts; replay dead letters (`payout-service`); search |
| `ADMIN` | everything |
| `SERVICE` | machine accounts — `assessment-service` signs in to read photos |

Anonymous → `401`, wrong role or someone else's claim → `403`, both as `application/problem+json`.

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
mvn verify -Dperf       # additionally the performance ITs (tag `perf`, excluded by default)
```

### Code style

```bash
mvn spotless:apply      # format every Java module (Palantir Java Format: 4 spaces, 120 columns, import order)
mvn spotless:check      # what CI runs — fails on unformatted code
mvn checkstyle:check    # rules the formatter cannot enforce (config/checkstyle/checkstyle.xml)
```

Both checks are bound to `mvn verify`, so an unformatted file or a naming violation fails the build
locally, in GitHub Actions and in Jenkins. Checkstyle is opinionated on purpose: identifiers must be
whole words (no `e`, `r`, `svc`; even loop counters are `index`), no star imports, one top-level class
per file, `default` in every `switch`, utility classes without public constructors, methods under
80 lines and complexity under 12 (relaxed for tests). Spring Boot entry points are the only exemption.

Shared test fixtures come from `platform-commons`' test-jar: `TestJwtTokenFactory` mints tokens exactly
as claim-service does, `KafkaTestConsumer` accumulates records per key for `await()` assertions.

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

Guards and failure modes have their own ITs: `RedisGuardsIT` (idempotent replay, a failed request
does not burn the key, per-client limit with `Retry-After`, **window rollover with an injected
`Clock`**, a 40-request concurrent burst lets exactly 5 through — `INCR` is atomic — and 10 concurrent
requests with one `Idempotency-Key` create exactly one claim — `SET NX` is atomic); `SecurityIT`
(login, 401/403, ownership, review held by the caller, expired / forged / payload-tampered tokens);
`OutboxResilienceIT` (**Kafka container paused** mid-run: writes keep succeeding, nothing is published,
everything is relayed in order once the broker is unpaused and the choreography resumes).

### Performance

Two kinds, both against real infrastructure. Numbers below are from one run on a laptop under
Docker Desktop/WSL2 (Aug 2026); they are a baseline to compare before/after a change, not a claim
about production capacity.

**Testcontainers perf ITs** (`mvn verify -Dperf`; loose assertions, printed as `PERF` lines):

| Scenario | Result |
|---|---|
| `POST /api/v1/claims`, 20 threads × 25, MockMvc, real Postgres + Redis | p50 49 ms · p95 146 ms · p99 465 ms · 273 req/s |
| Outbox relay after that burst (poll 1 s, batch 100) | 500+ events drained in ~10 s |
| Choreography: 50 claims submitted → `PENDING_REVIEW` (2 Kafka hops each) | 1.2 s (40 claims/s) |
| payout-service: 200 `CLAIM_APPROVED`, **each delivered twice** | exactly 200 payouts, 2 saga steps each, 5.4 s (37 payouts/s) |
| search-service: 300 events → searchable | 7.2 s incl. ES refresh; fuzzy search p50 8 ms · p95 17 ms · p99 28 ms |

**k6 against the running Compose stack** (`perf/run.sh`, k6 in Docker, 20 VUs, 30 s, logs in as a
policyholder, one rate-limit bucket per VU; run claim-service with `RATE_LIMIT_SUBMIT_PER_MINUTE=1000000`
for the duration, as the script's header explains):

| Metric | Value |
|---|---|
| requests | 4 981 in 30 s, **164 req/s** at 20 VUs with 100 ms think time |
| submit latency | avg 19.8 ms · p50 13.5 ms · p90 30.5 ms · **p95 39.5 ms** · max 428 ms |
| failures / rate-limited | 0 / 0 (thresholds `p(95)<300`, `failed<1%` pass) |

Every submit above went through the Redis idempotency + rate-limit filters, JWT verification, Bean
Validation, a Postgres transaction with the outbox row, and was later relayed to Kafka and triaged.

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
- **Keycloak / an external IdP** — the platform issues its own JWTs. For a single-team demo an
  OIDC server is another ~400 MB and a second source of truth for accounts; the tokens here are
  standard JWTs, so swapping the issuer for Keycloak later changes `JwtTokens` and the login page only.
- **Paid LLM APIs and heavyweight ML runtimes** — triage is ImageNet MobileNetV2 on ONNX Runtime
  (no torch, ~15 ms per image on CPU) plus a text model; deterministic, so tests stay deterministic.
- **A process engine (Camunda)** — the first version used embedded Camunda 7 for the review task and
  the saga; it was replaced by event choreography to remove the orchestrator as a single point of
  coupling. Camunda remains the right answer when business users must own the process diagram.
- **Spring Cloud Eureka / Config Server** — Kubernetes already does discovery and config.

## Layout

```
platform-commons/       Shared infrastructure library (no JPA): Kafka dead-lettering, W3C trace propagation,
                        JWT resource-server security (decoder, role converter, statelessBearerApi), ProblemDetails,
                        logback-platform.xml; test-jar with TestJwtTokenFactory and KafkaTestConsumer
platform-outbox/        Transactional outbox library: OutboxEvent, OutboxEventRepository (SKIP LOCKED), OutboxWriter,
                        OutboxPublisher (scheduled relay), OutboxTraceContext (traceparent across the relay)
claim-service/          Spring Boot 3 / Java 21 — the claim aggregate, accounts and tokens, review queue
  src/main/java/com/kmultan/claims/
    domain/             Claim, ClaimStatus, Severity, ClaimPhoto, ProcessedMessage, events, auth/ (UserAccount, Role)
    application/        ClaimService (use cases), ClaimTimeoutScheduler (SLA escalation, triage timeout),
                        IdempotentConsumer, ClaimMetrics, assessment/ (AssessmentProvider port, Assessment)
    api/                ClaimController, ReviewController, AuthController, ClaimAccessPolicy, ClaimResponseAssembler,
                        GlobalExceptionHandler, dto/ (one record per file)
    infrastructure/
      consumers/        AssessmentEventListener, PayoutEventListener (+ own copies of the event envelopes)
      kafka/            KafkaTopicConfiguration
      outbox/           OutboxDomainEventPublisher (domain port -> platform-outbox)
      redis/            ClaimSubmissionIdempotencyFilter, ClaimSubmissionRateLimitFilter, ClockConfiguration
      security/         SecurityConfiguration, JwtTokenService, AuthenticatedUser, AuthenticationProperties, DemoAccountSeeder
      assessment/       HeuristicAssessmentProvider (timeout fallback)
  src/main/resources/db/migration/   Flyway V1 … V6 (claim, outbox, saga columns, trace context, choreography, accounts)
payout-service/         Spring Boot 3 — saga participant: PayoutSaga (one transaction per reaction), ledger entities,
                        ClaimEventListener / PayoutEventListener, StubPaymentGateway, DeadLetterQueueController
search-service/         Spring Boot 3 — ClaimEventListener -> ClaimDocumentIndexer (claims) + ClaimEventLogIndexer
                        (claim-events), ClaimSearchService, SearchController, SearchIndexInitializer
assessment-service/     FastAPI + Kafka consumer — MobileNetV2 (ONNX) + text model; service-account login for photos
adjuster-console/       Next.js 14 + TypeScript — login, then per role: /claims, /reviews, /finance
contracts/pacts/        Pact message contract payout-service ⇄ claim-service (consumer-written, provider-verified)
infra/                  postgres init, kibana data views, jenkins image + JCasC, observability configs
deploy/helm/            claims-platform chart; deploy/kind/up.sh
perf/                   k6 ingestion load test (perf/run.sh runs it in Docker)
docker-compose.yml      profiles: core (Postgres, Kafka, Redis, claim/payout/assessment), search (+Kibana), console, observability, ci (Jenkins)
Jenkinsfile             same pipeline as GitHub Actions, for a Jenkins agent with Docker
```

Conventions: every class name says what the class is (`*Configuration`, `*Listener`, `*Controller`,
`*Repository`, `*Properties`, `*Policy`, `*Assembler`); one listener per topic, one DTO per file;
no abbreviations in identifiers (`consumerRecord`, `objectMapper`, `claimService` — not `r`, `json`, `svc`);
each consumer keeps its own copy of the event envelopes it reads (no shared DTO library), while
infrastructure that is genuinely identical lives in `platform-commons` / `platform-outbox`.
Service images build from the repository root (`docker build -f claim-service/Dockerfile .`) because
of the library modules; Compose and the kind script already do this.
