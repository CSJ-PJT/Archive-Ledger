# Settlement Runbook

## Objective

Daily settlement selects transactions that are ready for settlement and records settlement batch/detail rows.

## Settlement Inclusion

Included:

- `finance_transaction.status = SETTLEMENT_READY`
- `occurred_at` date matches the requested settlement date

Excluded:

- `APPROVAL_REQUIRED`
- `REJECTED`
- `SETTLED`
- failed event rows
- duplicate event observations

## Run

```powershell
curl.exe -X POST "http://localhost:18080/api/settlements/daily/run?date=2026-01-15"
```

## Scheduled Run

Docker Compose enables scheduled settlement/reconciliation for local end-to-end demos:

```env
ARCHIVE_LEDGER_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_SETTLEMENT_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_RECONCILIATION_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_SCHEDULER_FIXED_DELAY_MS=60000
ARCHIVE_LEDGER_SCHEDULER_INITIAL_DELAY_MS=15000
```

The scheduler checks the configured settlement date and runs settlement only when `SETTLEMENT_READY` transactions exist for that date. This prevents repeated empty settlement batches while still allowing automatic settlement after Nexus/Logistics events arrive.

## Verify

```powershell
curl.exe http://localhost:18080/api/settlements
curl.exe http://localhost:18080/api/settlements/{batchId}
curl.exe http://localhost:18080/api/settlements/{batchId}/details
```

## Expected Result

- `settlement_batch.status = SUCCESS`
- `settlement_batch.total_transaction_count` equals included transaction count
- `settlement_detail` rows exist for included transactions
- included `finance_transaction` rows transition from `SETTLEMENT_READY` to `SETTLED`

## Approval Interaction

Approval-required transactions are not settled until callback:

```text
APPROVAL_REQUIRED --APPROVED--> SETTLEMENT_READY
APPROVAL_REQUIRED --REJECTED--> REJECTED
```

After an `APPROVED` callback, rerun settlement for the transaction's `occurred_at` date.
