# ADR 0007: Asynchronous payment gateway with webhook and timeout compensation

Status: Accepted

## Context
Real payment providers do not answer transfers synchronously: they accept, then confirm or
reject later on a webhook — and sometimes never answer at all. The original in-process stub
answered inline, which made the saga unrealistically simple.

## Decision
payment-gateway-simulator accepts a transfer with `202 ACCEPTED` and calls back later.
payout-service (mode `async`) keeps the payout `PENDING` with the reservation held, settles
or compensates on the webhook (shared-token auth, idempotent handler, any replica may take
it), and a sweep fails transfers the provider never confirmed, releasing the funds.

## Consequences
- The saga now has a genuine in-flight state and a timeout path — both covered by
  `AsynchronousGatewayIT` (settle, compensate, duplicate webhook, bad token, timeout).
- The synchronous stub remains the default for tests; Compose runs async by default.
