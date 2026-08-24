-- Idempotency ledger for the command consumer: one row per command handled.
create table processed_message (
    message_id   uuid primary key,
    message_type varchar(64) not null,
    processed_at timestamptz not null default now()
);

create table fund_reservation (
    claim_id    uuid primary key,
    amount      numeric(12,2) not null,
    status      varchar(16) not null,   -- RESERVED | RELEASED | SETTLED
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table payout (
    claim_id    uuid primary key,
    amount      numeric(12,2) not null,
    reference   varchar(64),
    status      varchar(16) not null,   -- ISSUED | REVERSED | FAILED
    reason      varchar(500),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table outbox_event (
    id             bigserial primary key,
    event_id       uuid not null unique,
    topic          varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id   uuid not null,
    event_type     varchar(64) not null,
    payload        jsonb not null,
    occurred_at    timestamptz not null,
    published_at   timestamptz
);
create index idx_outbox_unpublished on outbox_event (id) where published_at is null;
