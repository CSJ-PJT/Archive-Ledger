# Archive-Ledger

<p align="center">
  <img src="docs/brand/archive-os-logo.png" width="260" alt="ArchiveOS" />
</p>

Archive-Ledger is the **financial ledger and settlement backend** in the Archive Platform ecosystem.

- It receives synthetic cost events from **Archive-Nexus** (direct events).
- It receives logistics cost events from **Archive-Logistics** (native + compatibility events).
- It receives commerce events from **Archive-Market** and normalizes them to finance transactions.
- It creates `finance_transaction`, writes balanced `ledger_entry` rows (double-entry), runs settlement batches, performs reconciliation, and handles approval callbacks.

This service uses synthetic/demo data only. No real payment, card, account, logistics, location, customer personal, or banking data is used.

## 핵심 역할

- Archive-Nexus direct event ingestion
- Archive-Logistics native event ingestion
- Archive-Market event ingestion
- Finance transaction normalization
- Double-entry ledger entry creation
- Settlement batch and settlement exclusion control
- Reconciliation and mismatch detection
- Approval callback transition support
- Operational dashboards, actuator, and audit logging

## API

| Method | Path | Description |
| --- | --- | --- |
| POST | `/api/events/nexus` | Ingest one Archive-Nexus direct event |
| POST | `/api/events/nexus/bulk` | Ingest Nexus bulk events |
| POST | `/api/events/logistics` | Ingest one Logistics event |
| POST | `/api/events/logistics/bulk` | Ingest Logistics events (`{source, events}`) |
| POST | `/api/events/market` | Ingest one Archive-Market event |
| POST | `/api/events/market/bulk` | Ingest Market events (`{source, events}`) |
| GET | `/api/events/received` | List received events (`?source=` supported) |
| GET | `/api/events/received/{eventId}` | Get one received event |
| GET | `/api/transactions` | List transactions (`?status=`, `?source=` supported) |
| GET | `/api/transactions/{transactionId}` | Get one transaction |
| GET | `/api/ledger/entries` | List ledger entries (`?transactionId=` supported) |
| GET | `/api/ledger/summary` | Debit/credit summary (`?date=`, `?factoryId=`, `?source=` supported) |
| GET | `/api/settlements` | List settlement batches |
| GET | `/api/settlements/{batchId}` | Get settlement batch |
| GET | `/api/settlements/{batchId}/details` | Settlement details |
| POST | `/api/settlements/daily/run` | Run daily settlement |
| POST | `/api/reconciliation/daily` | Run daily reconciliation |
| GET | `/api/reconciliation/daily` | Get daily reconciliation |
| GET | `/api/reconciliation/summary` | Latest reconciliation summary |
| POST | `/api/approvals/callback` | Approval transition callback |
| GET | `/api/operations/summary` | Service operations summary |
| GET | `/api/workforce/summary` | Synthetic workforce capacity/backlog summary |
| GET | `/api/productivity/summary` | Productivity summary |
| GET | `/api/capacity/summary` | Capacity summary |
| POST | `/api/workforce/allocations` | Assign synthetic workforce for a workday |
| POST | `/api/workforce/workday/run` | Run synthetic workday capacity calculation |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/info` | Build/runtime info |
| GET | `/actuator/metrics` | Metrics |

### Source and compatibility

- 운영 문서 및 계약 표기는 `Archive-Logistics`, `Archive-Market`를 사용합니다.
- 하위/과거 호환을 위해 `Archive-Logitics`는 유지하고 있으며, 로직에서 compatibility 처리됩니다.

## 주요 운영 규칙

- Idempotency:
  - `received_event.event_id` unique
  - `received_event.idempotency_key` unique
  - `finance_transaction.source_event_id` unique
- Duplicate safe:
  - Duplicate event or idempotency returns `DUPLICATE`
  - No duplicate ledger entries are created
- Debit/Credit balance:
  - For each transaction: `sum(debit) == sum(credit)` by `transaction_id`
- Settlement exclusion:
  - `SETTLEMENT_READY` only is settlement target
  - `APPROVAL_REQUIRED`, `REJECTED`, failed, duplicates are excluded
- Reconciliation mismatch:
  - `expectedTransactionCount = max(0, received - duplicate)`
  - `mismatch = max(0, expectedTransactionCount - created - failed)`
  - status: `OK` / `WARNING`

## Archive-Market 이벤트 요약

Supported event types:

- `SALES_REVENUE_CONFIRMED`
- `PAYMENT_CAPTURED`
- `REFUND_REQUESTED`
- `CLAIM_COMPENSATION_CONFIRMED`
- `MARKET_SERVICE_FEE_PAID`
- `PAYMENT_PROCESSING_FEE_PAID`

Mapping:

- `SALES_REVENUE_CONFIRMED` -> `SALES_REVENUE`
- `PAYMENT_CAPTURED` -> `PAYMENT_CAPTURE`
- `REFUND_REQUESTED` -> `SALES_REFUND`
- `CLAIM_COMPENSATION_CONFIRMED` -> `CLAIM_COMPENSATION_EXPENSE`
- `MARKET_SERVICE_FEE_PAID` -> `MARKET_SERVICE_FEE`
- `PAYMENT_PROCESSING_FEE_PAID` -> `PAYMENT_PROCESSING_FEE`

금액은 `payload.amount`가 필수이며 0 초과여야 합니다.  
`APPROVAL_REQUIRED` 조건(고액/리스크/고위험고객/특정 이벤트 규칙)에 해당하면 정산 대상에서 제외됩니다.

Query:

- `GET /api/events/received?source=Archive-Market`
- `GET /api/transactions?source=Archive-Market`
- `GET /api/ledger/summary?source=Archive-Market`

## Operational Workforce

Archive-Ledger는 정산, 대사, 승인 검토 업무를 synthetic workforce 기반 capacity 모델로 계산한다.

- 실제 직원 이름, 급여, 개인정보는 사용하지 않는다.
- 모든 비용은 synthetic KRW다.
- workforce allocation이 없으면 baseline capacity `500`으로 동작한다.
- `ArchiveOS` 또는 `Archive-Market`을 allocation `sourceService`로 허용한다.
- `hopCount > maxHop`인 allocation은 거부한다.

주요 API:

```powershell
curl.exe "http://localhost:18080/api/workforce/summary?date=2026-07-10&sourceService=ArchiveOS"
curl.exe -X POST "http://localhost:18080/api/workforce/workday/run?date=2026-07-10&sourceService=ArchiveOS"
```

## 운영 Runbook

### Health

```powershell
curl.exe http://localhost:18080/actuator/health
curl.exe http://localhost:18080/api/operations/summary
curl.exe http://localhost:18080/api/reconciliation/summary
```

### Smoke test

```powershell
$payload = '{"source":"Archive-Market","events":[{"eventId":"evt-market-smoke-001","idempotencyKey":"MARKET:SALES_REVENUE_CONFIRMED:ORDER-0001","source":"Archive-Market","eventType":"SALES_REVENUE_CONFIRMED","schemaVersion":1,"occurredAt":"2026-01-15T10:45:00.000Z","payload":{"orderId":"ORDER-0001","amount":120000,"factoryId":"FAC-A","vendorId":"VENDOR-MARKET-01","originCode":"FAC-A","destinationCode":"DC-SEOUL-01","currency":"KRW"}}]}'
curl.exe -X POST "http://localhost:18080/api/events/market/bulk" -H "Content-Type: application/json" -d $payload
curl.exe "http://localhost:18080/api/transactions?source=Archive-Market"
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Market"
curl.exe "http://localhost:18080/api/operations/summary"
curl.exe http://localhost:18080/api/events/received?source=Archive-Market
```

### Approval safe flow

```powershell
curl.exe -X POST "http://localhost:18080/api/approvals/callback" -H "Content-Type: application/json" -d '{"approvalRequestId":"APR-...","transactionId":"TX-...","decision":"APPROVED","decidedBy":"operator","comment":"approved"}'
```

## 검증 명령

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat bootJar --no-daemon --console=plain
docker compose config --quiet
```

## 문서

- [Architecture](docs/architecture.md)
- [API Reference](docs/api-reference.md)
- [Nexus Direct Event Contract](docs/nexus-direct-event-contract.md)
- [Logistics Event Contract](docs/logistics-event-contract.md)
- [Market Event Contract](docs/market-event-contract.md)
- [Ledger Transaction Mapping](docs/ledger-transaction-mapping.md)
- [Settlement Agency Model](docs/settlement-agency-model.md)
- [Operational Workforce](docs/operational-workforce.md)
- [Settlement Runbook](docs/settlement-runbook.md)
- [Reconciliation Fix](docs/reconciliation-fix.md)
- [Operations Runbook](docs/operations-runbook.md)
- [Smoke Test](docs/smoke-test.md)
