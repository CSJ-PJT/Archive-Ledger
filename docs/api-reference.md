# API Reference

Base URL: `http://localhost:18080`

## Event Ingestion

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/events/nexus` | Ingest one Nexus direct event |
| POST | `/api/events/nexus/bulk` | Ingest Nexus direct events as an array |
| POST | `/api/events/logistics` | Ingest one Logistics cost event |
| POST | `/api/events/logistics/bulk` | Ingest Logistics cost events in `{ source, events }` format |
| POST | `/api/events/market` | Ingest one Market event |
| POST | `/api/events/market/bulk` | Ingest Market events in `{ source, events }` format |
| GET | `/api/events/received` | List recent received events |
| GET | `/api/events/received/{eventId}` | Get one received event |

Supported filters:

- `GET /api/events/received?source=Archive-Nexus`
- `GET /api/events/received?source=Archive-Logitics`
- `GET /api/events/received?source=Archive-Market`

## Transactions

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/transactions` | List recent transactions |
| GET | `/api/transactions/{transactionId}` | Get one transaction |

Supported filters:

- `status`
- `source`

Examples:

```powershell
curl.exe "http://localhost:18080/api/transactions?status=APPROVAL_REQUIRED"
curl.exe "http://localhost:18080/api/transactions?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/transactions?source=Archive-Market"
```

## Ledger

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/ledger/entries` | List ledger entries |
| GET | `/api/ledger/summary` | Summarize debit/credit totals |

Supported filters:

- `transactionId`
- `date`
- `factoryId`
- `source`

## Settlement

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/batches/daily/run?date=YYYY-MM-DD&approvedBy=operator` | Run approved daily settlement and reconciliation batch |
| GET | `/api/batches/daily` | List daily batch runs |
| GET | `/api/batches/daily/{runId}` | Get one daily batch run |
| POST | `/api/settlements/daily/run?date=YYYY-MM-DD` | Run daily settlement |
| GET | `/api/settlements` | List settlement batches |
| GET | `/api/settlements/{batchId}` | Get one settlement batch |
| GET | `/api/settlements/{batchId}/details` | List settlement details |

The daily batch endpoint records `approvedBy`, `triggerType`, settlement batch id, reconciliation status, settled transaction count, amount, and mismatch count in `daily_batch_run`.

## Reconciliation

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/reconciliation/daily?date=YYYY-MM-DD` | Run daily reconciliation |
| GET | `/api/reconciliation/daily?date=YYYY-MM-DD` | Get latest reconciliation for date |
| GET | `/api/reconciliation/summary` | Get latest reconciliation result |

## Approval

```http
POST /api/approvals/callback
Content-Type: application/json

{
  "approvalRequestId": "APR-20260709-BBBC52B6-A",
  "transactionId": "TX-20260709-E4DD5558-6",
  "decision": "APPROVED",
  "decidedBy": "archiveos-operator",
  "comment": "Approved by policy gate"
}
```

`APPROVED` transitions the transaction to `SETTLEMENT_READY`. Other decisions are treated as rejection and transition the transaction to `REJECTED`.

## Operational Workforce

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/workforce/summary` | Synthetic workforce capacity/backlog summary |
| GET | `/api/productivity/summary` | Productivity summary |
| GET | `/api/capacity/summary` | Capacity summary |
| POST | `/api/workforce/allocations` | Assign synthetic workforce for a workday |
| POST | `/api/workforce/workday/run?date=YYYY-MM-DD` | Calculate daily capacity, processed count, backlog, cost, and productivity |
| GET | `/api/settlement-agency/summary` | Settlement agency revenue/cost summary with workforce impact |

Supported allocation sources:

- `ArchiveOS`
- `Archive-Market`

When no allocation is enabled for a workday, Ledger uses baseline capacity.

## Operations

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/operations/summary` | Service status, event counts, transaction counts, source breakdown |
| GET | `/actuator/health` | Spring Actuator health |
