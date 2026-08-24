-- Transactional outbox. Rows are inserted in the same transaction as the
-- aggregate change and published to Kafka by OutboxPublisher afterwards.
-- `id` is a monotonically increasing sequence, so it doubles as an ordering
-- / external-version number for downstream projections.
create table outbox_event (
    id             bigserial    primary key,
    event_id       uuid         not null unique,
    aggregate_type varchar(64)  not null,
    aggregate_id   uuid         not null,
    event_type     varchar(64)  not null,
    payload        jsonb        not null,
    occurred_at    timestamptz  not null,
    published_at   timestamptz
);

create index idx_outbox_unpublished on outbox_event (id) where published_at is null;
