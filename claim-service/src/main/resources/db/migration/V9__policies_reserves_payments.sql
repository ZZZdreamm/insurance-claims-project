-- Policies: coverage the claim is validated against; holder null = open (fleet/demo) policy.
create table policy (
    policy_number     varchar(40)   primary key,
    holder_account_id uuid,
    coverage_type     varchar(10)   not null,
    valid_from        date          not null,
    valid_to          date          not null,
    sum_insured       numeric(12,2) not null,
    deductible        numeric(12,2) not null default 0
);
create index idx_policy_holder on policy(holder_account_id);

-- Claims reserve: the insurer's expected remaining cost of an open claim.
create table claim_reserve (
    claim_id       uuid          primary key references claim(id),
    initial_amount numeric(12,2) not null,
    current_amount numeric(12,2) not null,
    status         varchar(20)   not null,
    opened_at      timestamptz   not null,
    updated_at     timestamptz   not null
);

-- One row per money movement to the policyholder (advance, final, retry).
create table claim_payment (
    id           uuid          primary key,
    claim_id     uuid          not null references claim(id),
    amount       numeric(12,2) not null,
    payment_type varchar(20)   not null,
    reference    varchar(100),
    issued_at    timestamptz   not null
);
create index idx_claim_payment_claim on claim_payment(claim_id);

-- Settlement breakdown and four-eyes bookkeeping on the claim itself.
alter table claim add column gross_approved_amount numeric(12,2);
alter table claim add column payable_amount        numeric(12,2);
alter table claim add column deductible_applied    numeric(12,2);
alter table claim add column paid_amount           numeric(12,2) not null default 0;
alter table claim add column first_approver        varchar(100);
alter table claim add column first_approved_at     timestamptz;
alter table claim add column fraud_flags           jsonb;

-- Photo fingerprint for the reused-photo fraud rule.
alter table claim_photo add column content_hash varchar(64);
