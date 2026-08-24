create sequence claim_number_seq start with 1 increment by 1;

create table claim (
    id               uuid primary key,
    claim_number     varchar(32)  not null unique,
    policy_number    varchar(32)  not null,
    plate_number     varchar(16)  not null,
    incident_date    date         not null,
    description      varchar(4000) not null,
    estimated_amount numeric(12, 2),
    approved_amount  numeric(12, 2),
    status           varchar(32)  not null,
    rejection_reason varchar(1000),
    version          bigint       not null default 0,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);

create index idx_claim_policy_number on claim (policy_number);
create index idx_claim_plate_number  on claim (plate_number);
create index idx_claim_status        on claim (status);
create index idx_claim_created_at    on claim (created_at desc);
