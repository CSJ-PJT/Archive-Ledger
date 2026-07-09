# Smoke Test

## Preconditions

```powershell
cd C:\Users\dan18\Documents\ArchivePJT\Archive-Ledger
docker compose up --build -d
```

## Basic Checks

```powershell
curl.exe http://localhost:18080/actuator/health
curl.exe http://localhost:18080/api/operations/summary
curl.exe http://localhost:18080/api/reconciliation/summary
```

Expected:

- health status is `UP`
- operations summary returns JSON
- reconciliation summary returns latest or newly generated summary

## Logistics Native Event

```powershell
$payload = '{"source":"Archive-Logitics","events":[{"eventId":"evt-logitics-smoke-001","idempotencyKey":"LOGITICS:LOGISTICS_COST_CONFIRMED:ROUTE-SMOKE-001","source":"Archive-Logitics","eventType":"LOGISTICS_COST_CONFIRMED","schemaVersion":1,"occurredAt":"2026-01-15T10:45:00.000Z","payload":{"routePlanId":"ROUTE-SMOKE-001","shipmentId":"SHIP-SMOKE-001","factoryId":"FAC-A","vendorId":"VENDOR-LOGISTICS-01","originCode":"FAC-A","destinationCode":"DC-SEOUL-01","totalCost":93420,"currency":"KRW","riskScore":0.42,"requiresApproval":false,"reason":"Synthetic logistics cost confirmed by Archive-Logistics","delayed":false,"deviated":false}}]}'
curl.exe -X POST "http://localhost:18080/api/events/logistics/bulk" -H "Content-Type: application/json" -d $payload
```

Expected:

- first request returns `accepted=1`
- duplicate request with the same payload returns `duplicate=1`
- no duplicate transaction or ledger entry is created

## Verify Ledger Balance

```powershell
curl.exe "http://localhost:18080/api/transactions?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Logitics"
curl.exe http://localhost:18080/api/ledger/entries
```

Expected:

- logistics transaction exists
- `totalDebit` equals `totalCredit`

## Settlement and Reconciliation

```powershell
curl.exe -X POST "http://localhost:18080/api/settlements/daily/run?date=2026-01-15"
curl.exe -X POST "http://localhost:18080/api/reconciliation/daily?date=2026-01-15"
curl.exe http://localhost:18080/api/reconciliation/summary
```

Expected:

- only `SETTLEMENT_READY` transactions are settled
- `APPROVAL_REQUIRED` and `REJECTED` transactions are excluded
- duplicate observations do not inflate mismatch
