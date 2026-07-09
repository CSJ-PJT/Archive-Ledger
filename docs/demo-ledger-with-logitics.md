# Demo: Archive-Ledger + Archive-Logitics

## 1) Start

```powershell
cd C:\Users\dan18\Documents\ArchivePJT\Archive-Ledger
docker compose up --build -d
```

### Health

```powershell
curl.exe http://localhost:18080/actuator/health
```

## 2) Logistics-native smoke (accepted + duplicate)

```powershell
$payload = '{"source":"Archive-Logitics","events":[{"eventId":"evt-logitics-final-smoke-001","idempotencyKey":"LOGITICS:LOGISTICS_COST_CONFIRMED:ROUTE-FINAL-SMOKE-001","source":"Archive-Logitics","eventType":"LOGISTICS_COST_CONFIRMED","schemaVersion":1,"occurredAt":"2026-01-15T10:45:00.000Z","payload":{"routePlanId":"ROUTE-FINAL-SMOKE-001","shipmentId":"SHIP-FINAL-SMOKE-001","factoryId":"FAC-A","vendorId":"VENDOR-LOGISTICS-01","originCode":"FAC-A","destinationCode":"DC-SEOUL-01","distanceKm":42,"estimatedMinutes":80,"fuelCost":60900,"tollCost":2520,"urgentSurcharge":30000,"delayPenalty":0,"coldChainPenalty":0,"totalCost":93420,"currency":"KRW","riskScore":0.42,"requiresApproval":false,"reason":"Synthetic logistics cost confirmed by Archive-Logitics","delayed":false,"deviated":false}}]}'
curl.exe -X POST "http://localhost:18080/api/events/logistics/bulk" -H "Content-Type: application/json" -d $payload
```

Duplicate check:

```powershell
curl.exe -X POST "http://localhost:18080/api/events/logistics/bulk" -H "Content-Type: application/json" -d $payload
```

Expected:
- first call -> `accepted:1`, `duplicate:0`
- second call -> `accepted:0`, `duplicate:1`

## 3) Compatibility event check

```powershell
curl.exe -X POST "http://localhost:18080/api/events/logistics" -H "Content-Type: application/json" -d '{"eventId":"evt-logitics-final-smoke-compat-001","idempotencyKey":"LOGITICS:LOGISTICS_DISPATCHED:ROUTE-FINAL-COMPAT-001","source":"Archive-Logitics","eventType":"LOGISTICS_DISPATCHED","schemaVersion":1,"occurredAt":"2026-01-15T12:00:00.000Z","payload":{"routePlanId":"ROUTE-FINAL-COMPAT-001","shipmentId":"SHIP-FINAL-COMPAT-001","factoryId":"FAC-C","vendorId":"VENDOR-LOGISTICS-01","originCode":"FAC-C","destinationCode":"DC-BUSAN-01","distanceKm":185,"estimatedMinutes":296,"fuelCost":268250,"tollCost":22200,"urgentSurcharge":30000,"delayPenalty":0,"coldChainPenalty":0,"totalCost":290450,"currency":"KRW","riskScore":0.12,"requiresApproval":false,"reason":"Compatibility mapping smoke","delayed":false,"deviated":false}}'
```

Expected: response `transactionType=LOGISTICS_COST`.

## 4) Verification

```powershell
curl.exe "http://localhost:18080/api/events/received?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/transactions?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/ledger/entries"
curl.exe http://localhost:18080/api/operations/summary
curl.exe http://localhost:18080/api/reconciliation/summary
```

## 5) Settlement / reconciliation smoke

```powershell
curl.exe -X POST "http://localhost:18080/api/settlements/daily/run?date=2026-07-09"
curl.exe http://localhost:18080/api/settlements
curl.exe -X POST "http://localhost:18080/api/reconciliation/daily?date=2026-07-09"
curl.exe http://localhost:18080/api/reconciliation/summary
```

## 6) ArchiveOS approval impact

- Approval-required event remains `APPROVAL_REQUIRED` and is excluded from settlement until callback.
- Callback path remains the existing `POST /api/approvals/callback`.
