# Nexus Direct Event Contract

Archive-Ledger keeps the Nexus direct ingestion path for synthetic direct cost events.

## Endpoints

- `POST /api/events/nexus`
- `POST /api/events/nexus/bulk`

## Bulk Request

`POST /api/events/nexus/bulk` accepts a JSON array of event envelopes.

```json
[
  {
    "eventId": "evt-nexus-smoke-001",
    "idempotencyKey": "NEXUS:MAINTENANCE_COMPLETED:FAC-A:SMOKE-001",
    "source": "Archive-Nexus",
    "eventType": "MAINTENANCE_COMPLETED",
    "schemaVersion": 1,
    "occurredAt": "2026-01-15T10:45:00.000Z",
    "payload": {
      "factoryId": "FAC-A",
      "vendorId": "VENDOR-MAINTENANCE-01",
      "estimatedCost": 120000,
      "currency": "KRW",
      "severity": "NORMAL",
      "reason": "Synthetic maintenance cost"
    }
  }
]
```

## Required Fields

- `eventId`
- `idempotencyKey`
- `eventType`
- `payload`

`source` defaults to `Archive-Nexus` when omitted on the Nexus endpoint.

## Processing Rules

- Event is saved to `received_event`.
- Duplicate `eventId` or `idempotencyKey` returns `DUPLICATE`.
- A normalized `finance_transaction` is created once per source event.
- Debit/credit `ledger_entry` rows are created for accepted transactions.
- `APPROVAL_REQUIRED` transactions are excluded from settlement until approval callback transitions them.

## Amount and Approval

Nexus direct events use the service's direct normalization rules. High amount or high severity events can become `APPROVAL_REQUIRED` according to the configured approval threshold and event payload.
