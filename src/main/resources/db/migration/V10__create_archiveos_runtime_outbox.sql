-- ArchiveOS runtime ingest is intentionally decoupled from Ledger business processing.
create table if not exists archiveos_runtime_outbox (
  id bigserial primary key,
  event_id varchar(200) not null unique,
  idempotency_key varchar(240) not null unique,
  correlation_id varchar(200),
  event_type varchar(100) not null,
  entity_id varchar(200) not null,
  payload text not null,
  delivery_status varchar(40) not null,
  retry_count integer not null default 0,
  last_error text,
  next_retry_at timestamp,
  published_at timestamp,
  created_at timestamp not null,
  updated_at timestamp not null
);

create index if not exists archiveos_runtime_outbox_delivery_idx
  on archiveos_runtime_outbox(delivery_status, next_retry_at, created_at);

create index if not exists archiveos_runtime_outbox_correlation_idx
  on archiveos_runtime_outbox(correlation_id, created_at desc);
