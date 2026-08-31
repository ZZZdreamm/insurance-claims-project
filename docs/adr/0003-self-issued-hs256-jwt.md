# ADR 0003: Self-issued HS256 JWTs

Status: Accepted (revisit before any real deployment)

## Context
The platform needs role-based access (POLICYHOLDER / ADJUSTER / FINANCE / ADMIN / SERVICE)
across three Java services and one Python service. Keycloak was prototyped and rejected for
this project: another 700 MB container and admin surface for a single-tenant demo.

## Decision
claim-service owns accounts (Postgres, BCrypt) and issues HS256 JWTs; every service verifies
them with a shared secret (`AUTH_JWT_SECRET`). The resource-server chain, role converter and
CORS live once in `platform-commons`.

## Consequences
- One fewer runtime dependency; login and token issuing are ~200 lines of auditable code.
- A shared symmetric secret means any service could mint tokens: acceptable inside one trust
  boundary, not across teams. The upgrade path is RS256 with a JWKS endpoint on
  claim-service — verifiers change one property, no API changes.
