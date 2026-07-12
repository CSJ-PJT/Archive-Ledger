-- Preserve the inbound runtime mesh context on every Ledger processing boundary.
-- A settlement batch can include several correlations, so the correlation belongs
-- to its transaction-level settlement_detail rather than the batch header.
alter table finance_transaction
  add column if not exists simulation_run_id varchar(120);

alter table finance_transaction
  add column if not exists settlement_cycle_id varchar(120);

alter table finance_transaction
  add column if not exists correlation_id varchar(200);

alter table finance_transaction
  add column if not exists causation_id varchar(200);

alter table ledger_entry
  add column if not exists correlation_id varchar(200);

alter table ledger_entry
  add column if not exists causation_id varchar(200);

alter table approval_request
  add column if not exists correlation_id varchar(200);

alter table approval_request
  add column if not exists causation_id varchar(200);

alter table approval_request
  add column if not exists settlement_cycle_id varchar(120);

alter table settlement_detail
  add column if not exists correlation_id varchar(200);

alter table settlement_detail
  add column if not exists causation_id varchar(200);

alter table settlement_detail
  add column if not exists simulation_run_id varchar(120);

alter table settlement_detail
  add column if not exists settlement_cycle_id varchar(120);

create index if not exists finance_transaction_correlation_idx
  on finance_transaction(correlation_id);

create index if not exists ledger_entry_correlation_idx
  on ledger_entry(correlation_id);

create index if not exists settlement_detail_correlation_idx
  on settlement_detail(correlation_id);
