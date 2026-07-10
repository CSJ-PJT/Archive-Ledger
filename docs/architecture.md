# Archive-Ledger Architecture

## Purpose

Archive-Ledger is the financial ledger service in the Archive Platform ecosystem. It receives synthetic business events, normalizes them into finance transactions, writes double-entry ledger entries, runs settlement batches, performs reconciliation, and handles approval callbacks.

## Service Boundary

Archive-Ledger owns:

- event ingestion for Nexus direct cost events
- event ingestion for Logistics cost-confirmed events
- event ingestion for Archive-Market events
- idempotency and duplicate-safe processing
- `finance_transaction` creation
- `ledger_entry` debit/credit creation
- settlement batch state
- reconciliation result state
- approval callback state changes
- audit log and operations summary

Archive-Ledger does not own:

- Nexus manufacturing event generation
- Logistics route calculation or outbox publishing
- ArchiveOS approval UI or evidence UI
- real banking, card, account, or personal data

## Runtime Flow

```text
Archive-Nexus
  -> POST /api/events/nexus/bulk
  -> received_event
  -> finance_transaction
  -> ledger_entry
  -> settlement_batch / reconciliation_result

Archive-Logistics
  -> POST /api/events/logistics/bulk
  -> received_event
  -> finance_transaction
  -> ledger_entry
  -> approval_request when required
  -> settlement_batch / reconciliation_result

Archive-Market
  -> POST /api/events/market/bulk
  -> received_event
  -> finance_transaction
  -> ledger_entry
  -> approval_request when required
  -> settlement_batch / reconciliation_result
```

Approval-required transactions can be connected to ArchiveOS:

```text
Archive-Ledger approval_request
  -> ArchiveOS external approval
  -> POST /api/approvals/callback
  -> SETTLEMENT_READY or REJECTED
```

## Persistence Model

- `received_event`: original event envelope and processing status
- `finance_transaction`: normalized finance transaction
- `ledger_entry`: double-entry accounting rows
- `settlement_batch`: daily settlement run header
- `settlement_detail`: settlement included transactions
- `reconciliation_result`: daily event/transaction/settlement consistency result
- `approval_request`: approval gate state
- `workforce_allocation`: synthetic workforce assignment by workday, role, and sourceService
- `workforce_workday_result`: calculated capacity, backlog, productivity, and synthetic operating cost
- `audit_log`: state transition and operational audit trail

## Operational Workforce

Archive-Ledger models settlement, reconciliation, and approval review as synthetic workforce-driven operations.
The workforce layer is additive: existing event ingestion, settlement, approval callback, and reconciliation contracts remain unchanged.

Ledger accepts workforce allocation from `ArchiveOS` or `Archive-Market` and calculates:

- baseline capacity
- allocated capacity
- daily demand
- processed count
- backlog and delayed count
- synthetic KRW operating cost
- productivity score
- bottleneck status

This gives ArchiveOS a control-plane contract for workforce allocation and bottleneck monitoring without introducing real employee, salary, or personal data.

## Failure Isolation

- Duplicate events do not create duplicate transactions.
- Validation failures are recorded as failed received events.
- Approval integration failures are audited as degraded approval requests and do not stop Ledger ingestion.
- Settlement only includes `SETTLEMENT_READY`, so `APPROVAL_REQUIRED` and `REJECTED` remain isolated from payout-like processing.

## Source Naming

External service documentation uses `Archive-Logistics`. Existing event contracts still use `source=Archive-Logitics` as a compatibility literal. Ledger keeps that literal supported to avoid breaking previously emitted events.
Market contracts use `source=Archive-Market`.
