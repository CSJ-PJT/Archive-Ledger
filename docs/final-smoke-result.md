# Final Smoke Result (Archive-Ledger, 2026-07-09)

## 1) Baseline checks

- `GET /actuator/health` -> `{"status":"UP"}`
- `GET /api/operations/summary` -> `status=HEALTHY`
- Source total at the time of check:
  - `receivedEvents=112`
  - `transactions=112`
  - `settled=49`
  - `approvalRequired=62`

## 2) Logistics-native event smoke

### Accepted case
- Endpoint: `POST /api/events/logistics/bulk`
- Event:
  - `eventId=evt-logitics-final-smoke-001`
  - `idempotencyKey=LOGITICS:LOGISTICS_COST_CONFIRMED:ROUTE-FINAL-SMOKE-001`
  - `eventType=LOGISTICS_COST_CONFIRMED`
- Result:
  - `accepted=1`, `duplicate=0`, `failed=0`
  - Created transaction: `TX-20260709-4A228489-8`

### Duplicate case
- Same payload re-posted immediately.
- Result:
  - `accepted=0`, `duplicate=1`, `failed=0`
  - No duplicated transaction or ledger entries created.

## 3) Compatibility event smoke

- Endpoint: `POST /api/events/logistics`
- Event:
  - `source=Archive-Logitics`
  - `eventType=LOGISTICS_DISPATCHED`
  - `eventId=evt-logitics-final-smoke-compat-001`
- Result:
  - `status=ACCEPTED`
  - Transaction type normalized to `LOGISTICS_COST`
  - Transaction: `TX-20260709-C1696814-A`

## 4) Approval-required smoke

- Endpoint: `POST /api/events/logistics/bulk`
- Event:
  - `eventId=evt-logitics-final-smoke-AR-001`
  - `eventType=URGENT_DELIVERY_COST_CONFIRMED`
  - `totalCost=500000`, `riskScore=0.92`
- Result:
  - Transaction created with `APPROVAL_REQUIRED`
  - Transaction: `TX-20260709-E4DD5558-6`
  - `approvalRequestId=APR-20260709-BBBC52B6-A`

## 5) Ledger debit/credit check

- Query `GET /api/transactions?source=Archive-Logitics` shows logistics transactions are mapped correctly.
- Example entry pair for `TX-20260709-4A228489-8`:
  - `LOGISTICS_EXPENSE` debit = 93,420
  - `ACCOUNTS_PAYABLE` credit = 93,420
- Summary balance by `source=Archive-Logitics`:
  - `totalDebit=2,060,210.00`
  - `totalCredit=2,060,210.00`
  - `entryCount=22`

## 6) Settlement & reconciliation

- `POST /api/settlements/daily/run?date=2026-07-09`
  - batch `SET-20260709-C3448D7F`
  - `totalTransactionCount=1`
  - `totalAmount=120000.00`
- `POST /api/reconciliation/daily?date=2026-07-09`
  - `receivedEvents=112`, `createdTransactions=112`
  - `logisticsEventCount=111`, `directEventCount=1`
  - `logisticsTransactionCount=11`, `directTransactionCount=1`
  - `settlementReady=1`, `settled=49`

## 7) Nexus direct path stability

- `POST /api/events/nexus/bulk` smoke verification:
  - `eventId=NEXUS-SMOKE-VERIFY-001`
  - Result `accepted=1`, `status=ACCEPTED`
  - Event appears in `GET /api/events/received?source=Archive-Nexus`

## 8) Notes

- No code changes were made in the previous step; this round applies the remaining reconciliation status issue.
- Legacy duplicate-only mismatch inflation is resolved in `reconcile()`; reconciliation status now reflects only non-duplicate non-failed imbalances.
