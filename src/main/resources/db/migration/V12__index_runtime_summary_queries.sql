-- Keep the ArchiveOS service/status reads on bounded index scans.
-- The recent-event query previously used a parallel full-table sort which could
-- exhaust PostgreSQL's Docker /dev/shm and surface as HttpTimeoutException.
create index if not exists received_event_received_at_idx
  on received_event(received_at desc);

create index if not exists received_event_status_received_at_idx
  on received_event(processing_status, received_at desc);

create index if not exists finance_transaction_created_at_idx
  on finance_transaction(created_at desc);

create index if not exists finance_transaction_status_created_at_idx
  on finance_transaction(status, created_at desc);

create index if not exists finance_transaction_status_occurred_at_idx
  on finance_transaction(status, occurred_at desc);

create index if not exists ledger_entry_created_at_idx
  on ledger_entry(created_at desc);

create index if not exists settlement_detail_status_created_at_idx
  on settlement_detail(status, created_at desc);

create index if not exists approval_request_status_requested_at_idx
  on approval_request(status, requested_at desc);

create index if not exists approval_request_status_decided_at_idx
  on approval_request(status, decided_at desc);

create index if not exists audit_log_action_created_at_idx
  on audit_log(action, created_at desc);

create index if not exists reconciliation_result_created_at_idx
  on reconciliation_result(created_at desc);

create index if not exists settlement_batch_completed_at_idx
  on settlement_batch(completed_at desc);

create index if not exists ledger_workday_result_created_at_idx
  on ledger_workday_result(created_at desc);
