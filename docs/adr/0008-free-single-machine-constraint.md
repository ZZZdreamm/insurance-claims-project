# ADR 0008: Free, single-machine constraint

Status: Accepted (governs every other decision)

## Context
This is a portfolio system: it must run fully on one developer PC (~5 GB RAM for the whole
stack, ~2 GB for the core) with zero paid services, while still demonstrating patterns that
transfer to production insurance platforms.

## Decision
Cut: Camunda 8 (SaaS-oriented), MongoDB (Postgres `jsonb` suffices), paid LLM APIs (a local
MobileNetV2 ONNX model instead), Eureka/Config Server (Compose DNS + env), cloud object
storage (photos as `bytea`). Kept, because they are free and carry interview weight: Kafka
(KRaft, native image), Elasticsearch + Kibana, full observability stack, Jenkins, Redis.

## Consequences
- Every component is memory-capped in Compose; profiles (`core`, `search`, `console`,
  `observability`, `ci`) keep the default footprint small.
- The Helm chart deploys the same images to kind, single-replica and `emptyDir`-backed —
  explicitly a demo topology, with the production alternative named in the README.
