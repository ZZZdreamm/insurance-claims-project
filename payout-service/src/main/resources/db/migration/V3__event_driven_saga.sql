-- Reservations and payouts can be retried after a failure: a claim may be
-- re-approved (PAYOUT_FAILED -> APPROVED), so status transitions are updates on
-- the same row rather than new rows.
alter table payout add column causation_event_id uuid;
alter table fund_reservation add column causation_event_id uuid;
