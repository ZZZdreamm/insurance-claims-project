create table user_account (
    id            uuid primary key,
    username      varchar(64)  not null unique,
    password_hash varchar(100) not null,
    display_name  varchar(120) not null,
    roles         varchar(200) not null,   -- comma-separated: POLICYHOLDER,ADJUSTER,FINANCE,ADMIN,SERVICE
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now()
);

-- a claim belongs to the policyholder who submitted it
alter table claim add column owner_id uuid;
create index idx_claim_owner on claim (owner_id);
