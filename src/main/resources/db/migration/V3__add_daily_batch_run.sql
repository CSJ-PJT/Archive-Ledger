create table if not exists daily_batch_run (
  id bigserial primary key,
  run_id varchar(80) not null unique,
  batch_date date not null,
  status varchar(40) not null,
  approved_by varchar(120) not null,
  trigger_type varchar(40) not null,
  settlement_enabled boolean not null,
  reconciliation_enabled boolean not null,
  settlement_batch_id varchar(80),
  reconciliation_status varchar(40),
  settlement_transaction_count integer not null default 0,
  settlement_amount numeric(19,2) not null default 0,
  mismatch_count integer not null default 0,
  started_at timestamp not null,
  completed_at timestamp,
  failure_reason text
);

create index if not exists daily_batch_run_date_idx
  on daily_batch_run(batch_date desc, started_at desc);

create index if not exists daily_batch_run_status_idx
  on daily_batch_run(status, started_at desc);
