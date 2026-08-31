-- Subrogation: recovering what we paid from the liable third party's insurer.
create table subrogation_case (
    id               uuid          primary key,
    claim_id         uuid          not null unique references claim(id),
    liable_party     varchar(200)  not null,
    expected_amount  numeric(12,2) not null,
    recovered_amount numeric(12,2) not null default 0,
    status           varchar(20)   not null,
    write_off_reason varchar(500),
    opened_by        varchar(100)  not null,
    opened_at        timestamptz   not null,
    updated_at       timestamptz   not null
);

-- Everything we told the policyholder, one row per message (channel simulated).
create table customer_communication (
    id       uuid         primary key,
    claim_id uuid         not null references claim(id),
    type     varchar(40)  not null,
    subject  varchar(200) not null,
    body     varchar(4000) not null,
    sent_at  timestamptz  not null
);
create index idx_customer_communication_claim on customer_communication(claim_id);
