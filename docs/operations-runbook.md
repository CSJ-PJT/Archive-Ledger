# Operations Runbook

## Daily Checks

```powershell
curl.exe http://localhost:18080/process.html
curl.exe http://localhost:18080/actuator/health
curl.exe http://localhost:18080/api/operations/summary
curl.exe http://localhost:18080/api/runtime/status
curl.exe http://localhost:18080/api/reconciliation/summary
curl.exe http://localhost:18080/api/workforce/summary
curl.exe http://localhost:18080/api/productivity/summary
curl.exe http://localhost:18080/api/capacity/summary
```

Check:

- service status is `HEALTHY`
- failed event count is not increasing unexpectedly
- duplicate count is explainable by retries
- last reconciliation status is `OK` or an explainable `WARNING`
- approval required count is expected before settlement
- ArchiveOS Workforce Overview summary APIs return HTTP 200

The browser dashboard at `/process.html` shows these same checks visually and refreshes every 30 seconds.

## Autonomous Runtime Work Loop

Local/demo runtime can run a limited autonomous work tick:

```env
ARCHIVE_RUNTIME_AUTORUN_ENABLED=true
ARCHIVE_RUNTIME_TICK_INTERVAL=30s
ARCHIVE_RUNTIME_MAX_EVENTS_PER_TICK=10
ARCHIVE_RUNTIME_MAX_BACKLOG_PER_TICK=50
```

Check runtime status:

```powershell
curl.exe http://localhost:18080/api/runtime/status
curl.exe "http://localhost:18080/api/runtime-events/recent?limit=20"
```

The loop updates workday, reconciliation, and runtime audit state. Summary GET APIs remain read-only.

For incremental ArchiveOS collection, store `latestCursor` from `/api/runtime/status` and pass it to the next request:

```powershell
curl.exe "http://localhost:18080/api/runtime-events/recent?after={cursor}&limit=100"
```

An invalid cursor returns an empty array and does not change Ledger state. `WAITING_FOR_DATA` means the service has no runtime projection yet; it is not an automatic failure state.

## Workforce Overview

ArchiveOS reads these Ledger endpoints as read-only runtime summaries:

```powershell
curl.exe http://localhost:18080/api/workforce/summary
curl.exe http://localhost:18080/api/productivity/summary
curl.exe http://localhost:18080/api/capacity/summary
```

Expected:

- `available=true`
- missing allocation returns `totalHeadcount=0` and `roles=[]`
- approval backlog maps to `bottleneckRole=APPROVAL_REVIEWER`
- settlement backlog maps to `bottleneckRole=SETTLEMENT_OPERATOR`
- reconciliation warning maps to `bottleneckRole=RECONCILIATION_ANALYST`
- callback failure maps to `bottleneckRole=CALLBACK_OPERATOR`

These GET APIs must not run settlement, reconciliation, approval callback, or fee creation.

## Automatic Daily Cycle

Docker/local defaults enable the scheduler:

```env
ARCHIVE_LEDGER_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_SETTLEMENT_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_RECONCILIATION_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_SCHEDULER_FIXED_DELAY_MS=60000
ARCHIVE_LEDGER_SCHEDULER_INITIAL_DELAY_MS=15000
```

Behavior:

- the scheduler runs the approved daily batch path only when `SETTLEMENT_READY` transactions exist for the configured date;
- `APPROVAL_REQUIRED`, `REJECTED`, duplicates, and failed events remain excluded;
- reconciliation runs periodically and refreshes `/api/reconciliation/summary`;
- manual APIs remain available for date-specific recovery runs.

## Event Triage

```powershell
curl.exe "http://localhost:18080/api/events/received?source=Archive-Nexus"
curl.exe "http://localhost:18080/api/events/received?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/events/received?source=Archive-Market"
```

Use `Archive-Logitics` only as the compatibility source value for persisted Logistics events. New events may use `Archive-Logistics`; Ledger counts and filters both source literals as Logistics.

`Archive-Market` queries should be used for commerce/claim/fee transactions and for operations separation checks:

```powershell
curl.exe "http://localhost:18080/api/operations/summary" | ConvertFrom-Json | ConvertTo-Json -Depth 3
```

Key fields to watch:

- `eventsReceivedFromMarket`
- `marketRevenueTransactions`
- `paymentCaptureTransactions`
- `refundTransactions`
- `claimCompensationTransactions`

## Transaction Triage

```powershell
curl.exe "http://localhost:18080/api/transactions?status=APPROVAL_REQUIRED"
curl.exe "http://localhost:18080/api/transactions?status=SETTLEMENT_READY"
curl.exe "http://localhost:18080/api/transactions?source=Archive-Market"
curl.exe "http://localhost:18080/api/transactions?source=Archive-Logitics"
```

If settlement is not picking up an expected transaction, confirm:

- status is `SETTLEMENT_READY`
- `occurred_at` date matches the settlement date
- transaction is not already `SETTLED`

## Ledger Balance

```powershell
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Nexus"
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Market"
```

`totalDebit` should equal `totalCredit` for the queried scope.

## Approval Callback

Approval-required transactions remain excluded from settlement until callback.

```powershell
curl.exe -X POST "http://localhost:18080/api/approvals/callback" -H "Content-Type: application/json" -d '{"approvalRequestId":"APR-...","transactionId":"TX-...","decision":"APPROVED","decidedBy":"archiveos-operator","comment":"Approved"}'
```

Expected:

- `APPROVED` -> `SETTLEMENT_READY`
- rejected decisions -> `REJECTED`

After approval, run the auditable daily batch:

```powershell
curl.exe -X POST "http://localhost:18080/api/batches/daily/run?date=YYYY-MM-DD&approvedBy=archiveos-operator"
curl.exe http://localhost:18080/api/batches/daily
```

## Mismatch Investigation

1. Run `GET /api/reconciliation/summary`.
2. If `mismatch > 0`, run `POST /api/reconciliation/daily?date=YYYY-MM-DD` again after checking event processing status.
3. Compare received events against transactions by `source_event_id`.
4. Exclude duplicate observations from investigation.
5. Investigate failed events separately from missing transactions.

## Safe Operating Assumptions

- Synthetic data only.
- No real card numbers, account numbers, personal data, tokens, or private keys.
- ArchiveOS, Archive-Nexus, and Archive-Logistics remain independent services.
