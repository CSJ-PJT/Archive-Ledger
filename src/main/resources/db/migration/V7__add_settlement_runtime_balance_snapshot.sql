create table if not exists ledger_runtime_balance_snapshot (
  id bigserial primary key,
  work_date date not null unique,
  settlement_cycle_id varchar(120),
  transaction_processing_revenue numeric(19,2) not null,
  settlement_agency_revenue numeric(19,2) not null,
  reconciliation_revenue numeric(19,2) not null,
  approval_review_revenue numeric(19,2) not null,
  workforce_cost numeric(19,2) not null,
  callback_failure_cost numeric(19,2) not null,
  operating_cost numeric(19,2) not null,
  operating_profit numeric(19,2) not null,
  operating_margin numeric(10,4) not null,
  cash_balance numeric(19,2) not null,
  transactions_received integer not null,
  transactions_processed integer not null,
  approval_backlog integer not null,
  settlement_backlog integer not null,
  reconciliation_backlog integer not null,
  callback_backlog integer not null,
  capacity_utilization numeric(10,4) not null,
  bottleneck_role varchar(80),
  settlement_delay_rate numeric(10,4) not null,
  negative_profit_streak integer not null default 0,
  calculated_at timestamp not null
);

create index if not exists ledger_runtime_balance_snapshot_cycle_idx
  on ledger_runtime_balance_snapshot(settlement_cycle_id, work_date desc);
