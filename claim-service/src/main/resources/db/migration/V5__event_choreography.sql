-- Camunda is gone: review tasks, SLA tracking and triage results live on the claim itself,
-- photos are stored with the claim, and consumed events are deduplicated locally.
alter table claim add column severity            varchar(16);
alter table claim add column assessment_provider varchar(64);
alter table claim add column review_assignee     varchar(64);
alter table claim add column review_due_at       timestamptz;
alter table claim add column escalated_at        timestamptz;
update claim set status = 'SUBMITTED' where status = 'UNDER_ASSESSMENT';

create table claim_photo (
    id           uuid primary key,
    claim_id     uuid not null references claim (id),
    content_type varchar(64) not null,
    size_bytes   integer not null,
    data         bytea not null,
    created_at   timestamptz not null default now()
);
create index idx_claim_photo_claim on claim_photo (claim_id);

create table processed_message (
    message_id   uuid primary key,
    message_type varchar(64) not null,
    processed_at timestamptz not null default now()
);

create index idx_claim_review_due on claim (review_due_at) where status = 'PENDING_REVIEW' and escalated_at is null;
