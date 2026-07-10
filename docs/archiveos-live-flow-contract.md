# ArchiveOS Live Flow Contract

Archive-Ledger는 ArchiveOS Live Flow / Operational Twin이 read-only로 수집할 수 있는 운영 데이터를 제공한다.

모든 데이터는 Synthetic Runtime Data다. 실제 개인정보, 실제 결제정보, 실제 금융정보, 실제 직원정보는 저장하거나 노출하지 않는다.

## Read-only Collection API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/runtime-events/recent?limit=100` | 최근 runtime event 조회 |
| `GET` | `/api/runtime-events/correlation/{correlationId}` | correlation 단위 흐름 추적 |
| `GET` | `/api/runtime-events/entity/{entityId}` | synthetic entity 단위 흐름 추적 |
| `GET` | `/api/operations/summary` | Ledger 운영 요약 |
| `GET` | `/api/runtime/status` | autonomous runtime loop 상태 |
| `GET` | `/api/workforce/summary` | synthetic workforce 요약 |
| `GET` | `/api/productivity/summary` | 생산성 요약 |
| `GET` | `/api/capacity/summary` | capacity/backlog 요약 |

ArchiveOS가 꺼져 있어도 Archive-Ledger는 정상 동작한다. 이 계약은 ArchiveOS가 pull 방식으로 읽는 조회 계약이다.

`/api/runtime/status`는 ArchiveOS가 Ledger pipeline이 살아 있는지 확인하는 runtime heartbeat/status 계약이다. status 조회 자체는 read-only이며 work tick을 실행하지 않는다.

세 summary API는 read-only다. 조회 중 DB insert, settlement 실행, reconciliation 실행, approval 처리, callback 전송, fee 생성은 수행하지 않는다.

## ArchiveOS Workforce Overview Fields

- `available`
- `totalHeadcount`
- `roles`
- `approvalBacklog`
- `settlementBacklog`
- `reconciliationBacklog`
- `callbackBacklog`
- `bottleneckRole`
- `latestEventAt`
- `productivityScore`
- `approvalReviewerCapacity`
- `settlementOperatorCapacity`

## Bottleneck Rule

- approval backlog가 가장 크면 `APPROVAL_REVIEWER`
- settlement backlog가 가장 크면 `SETTLEMENT_OPERATOR`
- reconciliation warning이 있으면 `RECONCILIATION_ANALYST`
- callback failure가 있으면 `CALLBACK_OPERATOR`
- 병목이 없으면 `NONE`

## Ledger Runtime Projection

Archive-Ledger는 다음 이벤트를 Live Flow용 projection으로 노출한다.

| Projection eventType | Meaning |
| --- | --- |
| `MARKET_REVENUE_RECEIVED` | Archive-Market 매출/결제/환불/클레임 계열 이벤트 수신 |
| `LOGISTICS_COST_RECEIVED` | Archive-Logistics 물류비/긴급배송/지연/우회/콜드체인 비용 이벤트 수신 |
| `NEXUS_COST_RECEIVED` | Archive-Nexus direct 비용 이벤트 수신 |
| `TRANSACTION_CREATED` | `finance_transaction` 생성 |
| `LEDGER_ENTRY_CREATED` | debit/credit `ledger_entry` 생성 |
| `APPROVAL_REQUIRED` | 승인 필요 거래 발생 |
| `APPROVAL_APPROVED` | 승인 callback 승인 |
| `APPROVAL_REJECTED` | 승인 callback 반려 |
| `SETTLEMENT_READY` | 정산 대기 상태 |
| `SETTLEMENT_COMPLETED` | 일 정산 완료 |
| `RECONCILIATION_OK` | 대사 정상 |
| `RECONCILIATION_WARNING` | 대사 경고 |
| `CALLBACK_SENT` | ArchiveOS callback 전송 |
| `CALLBACK_FAILED` | ArchiveOS callback 실패 |
| `WORKFORCE_ALLOCATION_ASSIGNED` | synthetic workforce 배정 |
| `WORKDAY_COMPLETED` | workday capacity 계산 완료 |
| `APPROVAL_BACKLOG_INCREASED` | 승인 backlog 증가 |
| `SETTLEMENT_DELAYED` | 정산 지연 발생 |

## Flow

```text
Archive-Market / Archive-Nexus / Archive-Logistics
  -> Archive-Ledger event receiver
  -> received_event
  -> finance_transaction
  -> ledger_entry
  -> settlement / reconciliation / approval callback
  -> runtime-events read model
  -> ArchiveOS Live Flow
```

## Guard

- `eventId`, `idempotencyKey`로 duplicate를 방지한다.
- `correlationId`, `causationId`로 cross-service 흐름을 추적한다.
- `hopCount`, `maxHop`으로 무한 순환을 방지한다.
- metadata에는 synthetic identifier만 포함한다.
- 금액은 raw amount 대신 `amountBucket`, `syntheticKrwRange`로 노출한다.

## Outbox

Archive-Ledger는 현재 외부 발행 outbox를 운영하지 않는다. 따라서 `/api/operations/summary`의 `outbox` 값은 계약 호환을 위해 0으로 노출한다.
