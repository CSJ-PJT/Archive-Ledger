# Operations Runbook

## Daily Checks

```powershell
curl.exe http://localhost:18080/process.html
curl.exe http://localhost:18080/actuator/health
curl.exe http://localhost:18080/api/operations/summary
curl.exe http://localhost:18080/api/reconciliation/summary
```

Check:

- service status is `HEALTHY`
- failed event count is not increasing unexpectedly
- duplicate count is explainable by retries
- last reconciliation status is `OK` or an explainable `WARNING`
- approval required count is expected before settlement

The browser dashboard at `/process.html` shows these same checks visually and refreshes every 30 seconds.

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

- settlement runs only when `SETTLEMENT_READY` transactions exist for the configured date;
- `APPROVAL_REQUIRED`, `REJECTED`, duplicates, and failed events remain excluded;
- reconciliation runs periodically and refreshes `/api/reconciliation/summary`;
- manual APIs remain available for date-specific recovery runs.

## Event Triage

```powershell
curl.exe "http://localhost:18080/api/events/received?source=Archive-Nexus"
curl.exe "http://localhost:18080/api/events/received?source=Archive-Logitics"
```

Use `Archive-Logitics` only as the compatibility source value for persisted Logistics events. New events may use `Archive-Logistics`; Ledger counts and filters both source literals as Logistics.

## Transaction Triage

```powershell
curl.exe "http://localhost:18080/api/transactions?status=APPROVAL_REQUIRED"
curl.exe "http://localhost:18080/api/transactions?status=SETTLEMENT_READY"
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
