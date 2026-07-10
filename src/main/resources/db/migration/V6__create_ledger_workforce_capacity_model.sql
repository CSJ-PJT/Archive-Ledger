create table if not exists ledger_workforce_allocation (
  id bigserial primary key,
  allocation_id varchar(100) not null unique,
  workday_id varchar(100) not null,
  work_date date not null,
  simulation_run_id varchar(120),
  settlement_cycle_id varchar(120),
  source_service varchar(100) not null,
  target_service varchar(100) not null,
  role_type varchar(80) not null,
  allocated_headcount integer not null,
  capacity_per_person_per_day integer not null,
  productivity_score numeric(8,4) not null,
  wage_per_day numeric(19,2) not null,
  effective_capacity integer not null,
  status varchar(40) not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create unique index if not exists ledger_workforce_allocation_workday_role_idx
  on ledger_workforce_allocation(workday_id, role_type);

create index if not exists ledger_workforce_allocation_date_idx
  on ledger_workforce_allocation(work_date, source_service, target_service);

create index if not exists ledger_workforce_allocation_source_idx
  on ledger_workforce_allocation(source_service, target_service, status);

create table if not exists ledger_workday_result (
  id bigserial primary key,
  workday_id varchar(100) not null unique,
  work_date date not null,
  baseline_capacity integer not null default 500,
  allocated_capacity integer not null default 500,
  transactions_received integer not null,
  transactions_processed integer not null,
  transactions_backlog integer not null,
  settlement_ready_count integer not null,
  settlement_completed_count integer not null,
  settlement_backlog_count integer not null default 0,
  reconciliation_processed_count integer not null,
  reconciliation_backlog_count integer not null default 0,
  approval_reviewed_count integer not null,
  approval_backlog_count integer not null,
  callback_processed_count integer not null,
  callback_failed_count integer not null,
  callback_backlog_count integer not null default 0,
  payroll_cost numeric(19,2) not null,
  backlog_cost numeric(19,2) not null default 0,
  productivity_score numeric(8,4) not null,
  bottleneck_role varchar(80),
  created_at timestamp not null
);

create index if not exists ledger_workday_result_date_idx
  on ledger_workday_result(work_date, created_at desc);
