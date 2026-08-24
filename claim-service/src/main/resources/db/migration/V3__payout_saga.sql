-- Outbox rows can now target different topics (claim events vs payout commands).
alter table outbox_event add column topic varchar(128) not null default 'claims.events';
alter table outbox_event alter column topic drop default;

alter table claim add column payout_failure_reason varchar(1000);
