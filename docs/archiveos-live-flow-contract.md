# ArchiveOS Live Flow Contract

Archive-Ledger는 ArchiveOS Live Flow / Operational Twin이 read-only로 수집할 수 있는 운영 데이터를 제공합니다.

모든 데이터는 Synthetic Runtime Data입니다. 실제 개인정보, 실제 결제정보, 실제 금융정보, 실제 직원정보는 저장하거나 노출하지 않습니다.

## Read-only 수집 API

| Method | Path | 용도 |
| --- | --- | --- |
| `GET` | `/api/runtime-events/recent?limit=100` | 최근 runtime event 조회 |
| `GET` | `/api/runtime-events/correlation/{correlationId}` | correlation 단위 흐름 추적 |
| `GET` | `/api/runtime-events/entity/{entityId}` | synthetic entity 단위 흐름 추적 |
| `GET` | `/api/operations/summary` | Ledger 운영 요약 |
| `GET` | `/api/workforce/summary` | synthetic workforce 요약 |
| `GET` | `/api/productivity/summary` | 생산성 요약 |
| `GET` | `/api/capacity/summary` | capacity/backlog 요약 |

ArchiveOS가 꺼져 있어도 Archive-Ledger는 정상 동작합니다. 이 계약은 ArchiveOS가 pull 방식으로 읽는 조회 계약입니다.

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

- `eventId`, `idempotencyKey`로 duplicate를 방지합니다.
- `correlationId`, `causationId`로 cross-service 흐름을 추적합니다.
- `hopCount`, `maxHop`으로 무한 순환을 방지합니다.
- metadata에는 synthetic identifier만 포함합니다.

## Outbox

Archive-Ledger는 현재 외부 발행 outbox를 운영하지 않습니다. 따라서 `/api/operations/summary`의 `outbox` 값은 계약 호환을 위해 0으로 노출합니다.

