create table if not exists workforce_allocation (
  id bigserial primary key,
  allocation_id varchar(100) not null unique,
  workday_id varchar(100) not null,
  work_date date not null,
  source_service varchar(100) not null,
  role varchar(80) not null,
  assigned_units integer not null,
  unit_cost_krw numeric(19,2) not null,
  productivity_multiplier numeric(8,4) not null default 1.0000,
  enabled boolean not null default true,
  simulation_run_id varchar(120),
  settlement_cycle_id varchar(120),
  correlation_id varchar(200),
  causation_id varchar(200),
  hop_count integer not null default 0,
  max_hop integer,
  created_at timestamp not null
);

create unique index if not exists workforce_allocation_day_role_idx
  on workforce_allocation(workday_id, source_service, role);

create index if not exists workforce_allocation_date_idx
  on workforce_allocation(work_date, source_service);

create table if not exists workforce_workday_result (
  id bigserial primary key,
  result_id varchar(100) not null unique,
  workday_id varchar(100) not null,
  work_date date not null,
  source_service varchar(100) not null,
  baseline_capacity integer not null,
  allocated_capacity integer not null,
  demand_count integer not null,
  processed_count integer not null,
  backlog_count integer not null,
  delayed_count integer not null,
  operating_cost_krw numeric(19,2) not null,
  productivity_score numeric(8,4) not null,
  bottleneck_detected boolean not null,
  status varchar(40) not null,
  created_at timestamp not null
);

create index if not exists workforce_workday_result_date_idx
  on workforce_workday_result(work_date, source_service, created_at desc);
