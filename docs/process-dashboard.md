# Process Dashboard

Archive-Ledger includes a static operational dashboard served by Spring Boot.

## URL

```text
http://localhost:18080/process.html
```

The root URL redirects to the same dashboard:

```text
http://localhost:18080/
```

## Purpose

The dashboard shows the full Ledger process on one screen:

1. Archive-Nexus direct event ingestion
2. Archive-Logistics native event ingestion
3. idempotency and duplicate-safe processing
4. finance transaction creation
5. double-entry ledger balance
6. approval gate
7. settlement
8. reconciliation

## Data Sources

The dashboard uses existing Ledger APIs from the same origin:

- `GET /actuator/health`
- `GET /api/operations/summary`
- `GET /api/reconciliation/summary`
- `GET /api/ledger/summary?source=Archive-Logitics`
- `GET /api/ledger/summary?source=Archive-Nexus`
- `GET /api/transactions`

External naming uses `Archive-Logistics`. The dashboard keeps `Archive-Logitics` in query examples because it is the existing compatibility source literal stored in Ledger events.

## Refresh

- Manual refresh: `새로고침` button
- Auto refresh: every 30 seconds

## Operating Checks

Use the dashboard to verify:

- service status is `HEALTHY`
- reconciliation status is `OK`
- mismatch is `0`
- logistics and Nexus ledger summaries have matching debit/credit totals
- approval-required transactions are visible before settlement
- recent transactions are being created with expected status
