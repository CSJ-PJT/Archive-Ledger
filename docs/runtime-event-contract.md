# Runtime Event Contract

`GET /api/runtime-events/*` API는 ArchiveOS Live Flow가 화면 애니메이션용 fake data를 만들지 않고 실제 Ledger runtime state를 읽도록 제공하는 read-only 계약입니다.

## Response Field

```json
{
  "eventId": "evt-market-001",
  "sourceService": "Archive-Market",
  "domain": "market",
  "eventType": "SALES_REVENUE_CONFIRMED",
  "entityType": "order",
  "entityId": "ORDER-001",
  "correlationId": "CORR-001",
  "causationId": "CAUSE-001",
  "status": "waiting",
  "severity": "info",
  "displayLabel": "Archive-Market SALES_REVENUE_CONFIRMED -> waiting",
  "occurredAt": "2026-01-15T10:45:00Z",
  "metadata": {
    "transactionId": "TX-20260115-ABCDEF",
    "transactionType": "SALES_REVENUE",
    "amount": 120000,
    "currency": "KRW",
    "simulationRunId": "SIM-001",
    "settlementCycleId": "CYCLE-001"
  }
}
```

## Status Mapping

| Ledger state | Runtime status |
| --- | --- |
| `FAILED` received event | `failed` |
| `APPROVAL_REQUIRED` transaction | `approval_required` |
| `REJECTED` transaction | `rejected` |
| `SETTLED` transaction | `settled` |
| `SETTLEMENT_READY` transaction | `waiting` |
| `PROCESSED` received event without transaction status | `completed` |
| unknown state | `unavailable` |

## Severity Mapping

| 조건 | severity |
| --- | --- |
| failed/rejected | `critical` |
| approval required, cold-chain risk, high risk score | `warning` |
| settlement waiting | `info` |
| normal processed flow | `normal` |

## Metadata Policy

허용:

- synthetic `orderId`, `paymentId`, `returnId`, `claimId`
- synthetic `routePlanId`, `shipmentId`, `factoryId`, `vendorId`
- `transactionId`, `transactionType`, `amount`, `currency`
- `simulationRunId`, `settlementCycleId`, `hopCount`, `maxHop`

금지:

- 실제 이름, 전화번호, 주소
- 카드번호, 계좌번호, 결제 토큰
- secret, token, password, webhook, private key

