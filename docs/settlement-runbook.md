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

## Approved Daily Batch

Use this endpoint when an operator approves settlement and wants settlement and reconciliation to run as one auditable daily cycle:

```powershell
curl.exe -X POST "http://localhost:18080/api/batches/daily/run?date=2026-01-15&approvedBy=archiveos-operator"
curl.exe http://localhost:18080/api/batches/daily
```

The run is stored in `daily_batch_run` with:

- approving actor
- trigger type
- settlement batch id
- settlement transaction count and amount
- reconciliation status
- mismatch count

## Settlement-Only Run

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

The scheduler checks the configured settlement date and runs the approved daily batch path only when `SETTLEMENT_READY` transactions exist for that date. This prevents repeated empty settlement batches while still allowing automatic settlement and reconciliation after Nexus/Logistics events arrive.

## Verify

```powershell
curl.exe http://localhost:18080/api/settlements
curl.exe http://localhost:18080/api/settlements/{batchId}
curl.exe http://localhost:18080/api/settlements/{batchId}/details
curl.exe http://localhost:18080/api/reconciliation/summary
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

Recommended approved flow:

```powershell
curl.exe -X POST "http://localhost:18080/api/approvals/callback" -H "Content-Type: application/json" -d "{\"approvalRequestId\":\"APR-...\",\"transactionId\":\"TX-...\",\"decision\":\"APPROVED\",\"decidedBy\":\"archiveos-operator\",\"comment\":\"Approved for settlement\"}"
curl.exe -X POST "http://localhost:18080/api/batches/daily/run?date=2026-01-15&approvedBy=archiveos-operator"
```
