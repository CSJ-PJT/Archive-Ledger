# Logistics Event Contract (Archive-Ledger)

This document defines the logistics-native event contract from Archive-Logistics to Archive-Ledger.

External documents use `Archive-Logistics`. The persisted event `source` value `Archive-Logitics` remains supported as an existing compatibility contract.

## Endpoints

- `POST /api/events/logistics`
- `POST /api/events/logistics/bulk`

Both endpoints accept the same event schema. Bulk format is:

```json
{
  "source": "Archive-Logitics",
  "events": [
    { ... one logistics event ... }
  ]
}
```

`POST /api/events/logistics` accepts one event object (same schema as above except `source` and wrapper are removed).

`POST /api/events/logistics/bulk` is batch form and also supports high-volume smoke batches (`1..10000` in service clients, service-side processing by chunk).

## Single event payload example

```json
{
  "eventId": "evt-logitics-20260115-000123",
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
    "reason": "Synthetic logistics cost confirmed by Archive-Logistics",
    "delayed": false,
    "deviated": false
  }
}
```

## Amount resolution

`amount` is resolved in this order:

1. `payload.totalCost`
2. `payload.estimatedCost`
3. `payload.amount`

If none exists or resolved amount <= 0, request is processed as failed.

If `payload.currency` is missing, default is `KRW`.

Validation failures (`amount <= 0` or unresolved amount) return `status=FAILED` in response item and are recorded as failed received events.

## Event type to transaction mapping

- `LOGISTICS_COST_CONFIRMED` -> `LOGISTICS_COST`
- `URGENT_DELIVERY_COST_CONFIRMED` -> `URGENT_DELIVERY_COST`
- `DELAY_PENALTY_CONFIRMED` -> `DELAY_PENALTY`
- `ROUTE_DEVIATION_COST_CONFIRMED` -> `ROUTE_DEVIATION_COST`
- `COLD_CHAIN_RISK_COST_CONFIRMED` -> `COLD_CHAIN_RISK_COST`
- `source=Archive-Logitics`, `eventType=LOGISTICS_DISPATCHED` -> `LOGISTICS_COST` (compatibility source value)

## Approval rule (logistics)

`APPROVAL_REQUIRED` is true when any condition is met:

- `requiresApproval == true`
- `totalCost >= 300000`
- `riskScore >= 0.85`
- `eventType == COLD_CHAIN_RISK_COST_CONFIRMED`
- `eventType == URGENT_DELIVERY_COST_CONFIRMED && totalCost >= 300000`
- `coldChainPenalty > 0`
- `delayed == true && requiresColdChain == true`
- `priority == CRITICAL`

Otherwise status is `SETTLEMENT_READY`.

## Duplicate handling

- duplicate `eventId` or `idempotencyKey` is rejected with `status=DUPLICATE`
- no duplicated transaction or ledger rows are created

`POST /api/events/logistics/bulk` processes each item independently so one duplicate or one failure does not rollback the whole batch.

## Compatibility mode

`Archive-Ledger` keeps existing `/api/events/nexus/*` path unchanged.
When a payload from compatibility source value `Archive-Logitics` arrives with `eventType=LOGISTICS_DISPATCHED`, it is handled as logistics confirmed cost and normalized as `LOGISTICS_COST`.

`sourceService` is stored in persisted events and transaction rows for source-level operations summary.
