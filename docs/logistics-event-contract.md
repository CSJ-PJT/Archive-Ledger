# Logistics Event Contract

This document describes the Archive-Logistics to Archive-Ledger native event contract.

External service naming uses `Archive-Logistics`. The event source literal `Archive-Logitics` remains supported for compatibility with the existing contract and query examples.

## Endpoints

- `POST /api/events/logistics`
- `POST /api/events/logistics/bulk`

## Bulk Request

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
        "totalCost": 93420,
        "currency": "KRW",
        "riskScore": 0.42,
        "requiresApproval": false,
        "reason": "Synthetic logistics cost confirmed by Archive-Logistics",
        "delayed": false,
        "deviated": false
      }
    }
  ]
}
```

## Supported Event Types

- `LOGISTICS_COST_CONFIRMED`
- `URGENT_DELIVERY_COST_CONFIRMED`
- `DELAY_PENALTY_CONFIRMED`
- `ROUTE_DEVIATION_COST_CONFIRMED`
- `COLD_CHAIN_RISK_COST_CONFIRMED`
- `LOGISTICS_DISPATCHED` when received from compatibility source `Archive-Logitics`

## Amount Resolution

Ledger resolves the transaction amount in this order:

1. `payload.totalCost`
2. `payload.estimatedCost`
3. `payload.amount`

If no amount is available or the amount is less than or equal to zero, the item is processed as failed.

Currency defaults to `KRW` when `payload.currency` is missing.

## Approval Rule

Logistics events become `APPROVAL_REQUIRED` when any condition is true:

- `payload.requiresApproval = true`
- `totalCost >= 300000`
- `riskScore >= 0.85`
- `eventType = COLD_CHAIN_RISK_COST_CONFIRMED`
- `eventType = URGENT_DELIVERY_COST_CONFIRMED` and `totalCost >= 300000`
- `coldChainPenalty > 0`
- `delayed = true` and `requiresColdChain = true`
- `priority = CRITICAL`

Otherwise the transaction is created as `SETTLEMENT_READY`.

## Duplicate Handling

- duplicate `eventId` or `idempotencyKey` returns `DUPLICATE`
- `finance_transaction.source_event_id` prevents transaction duplication
- duplicate events do not create ledger entries
- bulk ingestion handles each item independently

## Compatibility Mode

`source=Archive-Logitics` and `eventType=LOGISTICS_DISPATCHED` is normalized as `LOGISTICS_COST`. This supports the existing Archive-Ledger compatibility path while external documentation uses `Archive-Logistics`.
