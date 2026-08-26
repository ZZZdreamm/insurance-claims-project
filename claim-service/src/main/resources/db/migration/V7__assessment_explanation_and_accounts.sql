-- why the model decided what it decided, so a reviewer can see it without opening Kibana
alter table claim add column assessment_score numeric(6,2);
alter table claim add column assessment_explanation varchar(2000);
alter table claim add column assessed_at timestamptz;
alter table claim add column paid_at timestamptz;
alter table claim add column payout_reference varchar(64);
