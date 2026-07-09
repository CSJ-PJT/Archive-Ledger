# Demo: Archive-Ledger with Archive-Logitics

## 1) Start Archive-Ledger

```powershell
cd C:\Users\dan18\Documents\ArchivePJT\Archive-Ledger
docker compose up --build -d
```

## 2) Health check

```powershell
curl.exe http://localhost:18080/actuator/health
```

## 3) Send logistics smoke events

```powershell
curl.exe -X POST "http://localhost:18080/api/events/logistics/bulk" -H "Content-Type: application/json" -d @payload.json
```

`payload.json` example:

```json
{
  "source": "Archive-Logitics",
  "events": [
    {
      "eventId": "evt-logitics-smoke-001",
      "idempotencyKey": "LOGITICS:LOGISTICS_COST_CONFIRMED:ROUTE-SMOKE-001",
      "source": "Archive-Logitics",
      "eventType": "LOGISTICS_COST_CONFIRMED",
      "schemaVersion": 1,
      "occurredAt": "2026-01-15T10:45:00.000Z",
      "payload": {
        "routePlanId": "ROUTE-SMOKE-001",
        "shipmentId": "SHIP-SMOKE-001",
        "factoryId": "FAC-A",
        "vendorId": "VENDOR-LOGISTICS-01",
        "originCode": "FAC-A",
        "destinationCode": "DC-SEOUL-01",
        "distanceKm": 42,
        "estimatedMinutes": 80,
        "fuelCost": 60900,
        "tollCost": 2520,
        "urgentSurcharge": 30000,
        "delayPenalty": 0,
        "coldChainPenalty": 0,
        "totalCost": 93420,
        "currency": "KRW",
        "riskScore": 0.42,
        "requiresApproval": false,
        "reason": "Synthetic logistics cost confirmed by Archive-Logitics",
        "delayed": false,
        "deviated": false
      }
    },
    {
      "eventId": "evt-logitics-smoke-002",
      "idempotencyKey": "LOGITICS:URGENT_DELIVERY_COST_CONFIRMED:ROUTE-SMOKE-002",
      "source": "Archive-Logitics",
      "eventType": "URGENT_DELIVERY_COST_CONFIRMED",
      "schemaVersion": 1,
      "occurredAt": "2026-01-15T10:46:00.000Z",
      "payload": {
        "routePlanId": "ROUTE-SMOKE-002",
        "shipmentId": "SHIP-SMOKE-002",
        "factoryId": "FAC-B",
        "vendorId": "VENDOR-LOGISTICS-02",
        "originCode": "FAC-B",
        "destinationCode": "DC-DAEJEON-01",
        "distanceKm": 76,
        "estimatedMinutes": 122,
        "fuelCost": 110200,
        "tollCost": 9120,
        "urgentSurcharge": 70000,
        "delayPenalty": 0,
        "coldChainPenalty": 0,
        "totalCost": 250320,
        "currency": "KRW",
        "riskScore": 0.92,
        "requiresApproval": true,
        "reason": "Urgent risk adjustment",
        "delayed": true,
        "deviated": true
      }
    }
  ]
}
```

## 4) Verify Logitics-specific views

```powershell
curl.exe "http://localhost:18080/api/events/received?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/transactions?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/ledger/entries"
```

## 5) Daily reconciliation and operations

```powershell
$date=(Get-Date).ToString("yyyy-MM-dd")
curl.exe -X POST "http://localhost:18080/api/reconciliation/daily?date=$date"
curl.exe "http://localhost:18080/api/reconciliation/summary"
curl.exe "http://localhost:18080/api/operations/summary"
```

## Notes

- `LOGISTICS_DISPATCHED` sent from `Archive-Logitics` is intentionally mapped as `LOGISTICS_COST`.
- Approval-required transactions appear as `APPROVAL_REQUIRED` and are not settled until approval callback.
