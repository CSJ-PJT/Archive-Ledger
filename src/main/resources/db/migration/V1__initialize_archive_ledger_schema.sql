create table if not exists received_event (
  id bigserial primary key,
  event_id varchar(80) not null unique,
  idempotency_key varchar(160) not null unique,
  source varchar(80) not null,
  event_type varchar(80) not null,
  schema_version integer not null,
  payload text not null,
  processing_status varchar(40) not null,
  received_at timestamp not null,
  processed_at timestamp,
  failure_reason text
);

create table if not exists finance_transaction (
  id bigserial primary key,
  transaction_id varchar(80) not null unique,
  source_event_id varchar(80) not null unique,
  idempotency_key varchar(160) not null,
  transaction_type varchar(80) not null,
  factory_id varchar(40),
  vendor_id varchar(80),
  synthetic_account_id varchar(120),
  amount numeric(19,2) not null,
  currency varchar(8) not null,
  status varchar(40) not null,
  approval_required boolean not null,
  approval_request_id varchar(80),
  reason text,
  occurred_at timestamp not null,
  created_at timestamp not null,
  updated_at timestamp not null
);

create table if not exists ledger_entry (
  id bigserial primary key,
  transaction_id varchar(80) not null,
  account_code varchar(80) not null,
  account_name varchar(120) not null,
  debit_amount numeric(19,2) not null default 0,
  credit_amount numeric(19,2) not null default 0,
  factory_id varchar(40),
  vendor_id varchar(80),
  occurred_at timestamp not null,
  created_at timestamp not null
);

create index if not exists ledger_entry_transaction_idx on ledger_entry(transaction_id);

create table if not exists settlement_batch (
  id bigserial primary key,
  batch_id varchar(80) not null unique,
  settlement_date date not null,
  status varchar(40) not null,
  total_transaction_count integer not null,
  total_amount numeric(19,2) not null,
  started_at timestamp not null,
  completed_at timestamp,
  failure_reason text
);

create table if not exists settlement_detail (
  id bigserial primary key,
  batch_id varchar(80) not null,
  transaction_id varchar(80) not null,
  factory_id varchar(40),
  vendor_id varchar(80),
  account_code varchar(80) not null,
  amount numeric(19,2) not null,
  status varchar(40) not null,
  created_at timestamp not null
);

create table if not exists reconciliation_result (
  id bigserial primary key,
  reconciliation_date date not null,
  nexus_event_count integer not null,
  received_event_count integer not null,
  created_transaction_count integer not null,
  duplicate_event_count integer not null,
  failed_event_count integer not null,
  approval_required_count integer not null,
  settlement_ready_count integer not null,
  settled_count integer not null,
  mismatch_count integer not null,
  status varchar(40) not null,
  created_at timestamp not null
);

create index if not exists reconciliation_result_date_idx on reconciliation_result(reconciliation_date, created_at desc);

create table if not exists approval_request (
  id bigserial primary key,
  approval_request_id varchar(80) not null unique,
  transaction_id varchar(80) not null,
  requested_to varchar(120) not null,
  status varchar(40) not null,
  amount numeric(19,2) not null,
  reason text not null,
  policy_evidence text not null,
  requested_at timestamp not null,
  decided_at timestamp,
  decided_by varchar(120)
);

create table if not exists audit_log (
  id bigserial primary key,
  trace_id varchar(120) not null,
  actor varchar(120) not null,
  action varchar(120) not null,
  target_type varchar(80) not null,
  target_id varchar(120) not null,
  before_status varchar(40),
  after_status varchar(40),
  detail text not null,
  created_at timestamp not null
);

create index if not exists audit_log_target_idx on audit_log(target_type, target_id, created_at desc);
