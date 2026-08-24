-- W3C traceparent captured when the row is written, so the relay can continue the
-- originating trace instead of starting a new one from the poller thread.
alter table outbox_event add column trace_parent varchar(64);
