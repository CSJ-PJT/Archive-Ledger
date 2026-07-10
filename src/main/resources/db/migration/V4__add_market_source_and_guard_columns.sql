alter table received_event
  add column if not exists simulation_run_id varchar(120);

alter table received_event
  add column if not exists settlement_cycle_id varchar(120);

alter table received_event
  add column if not exists correlation_id varchar(200);

alter table received_event
  add column if not exists causation_id varchar(200);

alter table received_event
  add column if not exists hop_count integer not null default 0;

alter table received_event
  add column if not exists max_hop integer;

create index if not exists received_event_correlation_idx
  on received_event(source_service, event_type, correlation_id);

create index if not exists received_event_hop_idx
  on received_event(source_service, hop_count, max_hop);

alter table reconciliation_result
  add column if not exists market_event_count integer not null default 0;

alter table reconciliation_result
  add column if not exists market_transaction_count integer not null default 0;
