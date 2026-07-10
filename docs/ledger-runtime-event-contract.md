# Ledger Runtime Event Contract

ArchiveOS Live Flow는 Archive-Ledger의 실제 runtime state를 이 계약으로 읽습니다. 프론트엔드는 fake random animation 데이터를 만들지 않고, Ledger가 제공하는 projection event를 그대로 사용합니다.

## API

| Method | Path |
| --- | --- |
| `GET` | `/api/runtime-events/recent?limit=100` |
| `GET` | `/api/runtime-events/correlation/{correlationId}` |
| `GET` | `/api/runtime-events/entity/{entityId}` |

## 공통 필드

```json
{
  "eventId": "rt-transaction-TRANSACTION_CREATED-TX-001",
  "sourceService": "Archive-Market",
  "domain": "ledger",
  "eventType": "TRANSACTION_CREATED",
  "entityType": "transaction",
  "entityId": "TX-001",
  "correlationId": "CORR-001",
  "causationId": "CAUSE-001",
  "status": "completed",
  "severity": "normal",
  "displayLabel": "거래 생성 완료 1건",
  "occurredAt": "2026-01-15T10:45:00Z",
  "metadata": {
    "transactionId": "TX-001",
    "transactionType": "SALES_REVENUE",
    "amountBucket": "100K_TO_300K",
    "syntheticKrwRange": "100,000~300,000 KRW"
  }
}
```

## Projection Event Types

| eventType | status | severity |
| --- | --- | --- |
| `MARKET_REVENUE_RECEIVED` | `completed` | `normal` |
| `LOGISTICS_COST_RECEIVED` | `completed` | `normal` |
| `NEXUS_COST_RECEIVED` | `completed` | `normal` |
| `TRANSACTION_CREATED` | `completed` | `normal` |
| `LEDGER_ENTRY_CREATED` | `completed` | `normal` |
| `APPROVAL_REQUIRED` | `approval_required` | `warning` |
| `APPROVAL_APPROVED` | `approved` | `normal` |
| `APPROVAL_REJECTED` | `rejected` | `critical` |
| `SETTLEMENT_READY` | `waiting` | `info` |
| `SETTLEMENT_COMPLETED` | `settled` | `normal` |
| `RECONCILIATION_OK` | `completed` | `normal` |
| `RECONCILIATION_WARNING` | `delayed` | `warning` |
| `CALLBACK_SENT` | `completed` | `normal` |
| `CALLBACK_FAILED` | `failed` | `warning` |
| `WORKFORCE_ALLOCATION_ASSIGNED` | `completed` | `normal` |
| `WORKDAY_COMPLETED` | `completed` or `delayed` | `normal` or `warning` |
| `APPROVAL_BACKLOG_INCREASED` | `delayed` | `warning` |
| `SETTLEMENT_DELAYED` | `delayed` | `warning` |

## Metadata Policy

허용:

- synthetic ID: `orderId`, `paymentId`, `returnId`, `claimId`, `routePlanId`, `shipmentId`, `factoryId`, `vendorId`
- runtime trace: `correlationId`, `causationId`, `simulationRunId`, `settlementCycleId`
- masked amount: `amountBucket`, `syntheticKrwRange`
- operational count: `entryCount`, `transactionCount`, `approvalBacklog`, `settlementBacklog`

금지:

- raw 카드번호, 계좌번호, 결제 토큰
- 실제 이름, 전화번호, 주소
- secret, token, webhook, private key
- 실제 금융 데이터처럼 보이는 raw amount 노출

