alter table received_event
  add column source_service varchar(100);

alter table finance_transaction
  add column source_service varchar(100);

alter table finance_transaction
  add column route_plan_id varchar(100);

alter table finance_transaction
  add column shipment_id varchar(100);

alter table finance_transaction
  add column origin_code varchar(100);

alter table finance_transaction
  add column destination_code varchar(100);

alter table finance_transaction
  add column risk_score numeric(5,4);

alter table finance_transaction
  add column approval_reason text;

alter table ledger_entry
  add column source_service varchar(100);

alter table reconciliation_result
  add column logistics_event_count integer not null default 0;

alter table reconciliation_result
  add column direct_event_count integer not null default 0;

alter table reconciliation_result
  add column logistics_transaction_count integer not null default 0;

alter table reconciliation_result
  add column direct_transaction_count integer not null default 0;

create index if not exists finance_transaction_source_service_idx on finance_transaction(source_service);
create index if not exists received_event_source_service_idx on received_event(source_service);
create index if not exists ledger_entry_source_service_idx on ledger_entry(source_service);
