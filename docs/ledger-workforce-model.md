# Ledger Workforce Model

Archive-Ledger의 workforce 모델은 거래 처리, 원장 기록, 정산, 대사, 승인 검토, callback 대응을 synthetic workforce capacity로 계산한다.

실제 회계 담당자 이름, 급여, 개인정보는 사용하지 않는다. 모든 금액은 synthetic KRW다.

## Roles

| Role | 기본 1인 1일 capacity |
| --- | ---: |
| `TRANSACTION_PROCESSOR` | 100 transactions |
| `LEDGER_ACCOUNTANT` | 100 ledger entry operations |
| `SETTLEMENT_OPERATOR` | 50 settlement items |
| `RECONCILIATION_ANALYST` | 20 reconciliation issues |
| `APPROVAL_REVIEWER` | 30 approval requests |
| `CALLBACK_OPERATOR` | 50 callback/retry items |
| `LEDGER_MANAGER` | 200 coordination capacity |

각 allocation은 `allocatedHeadcount`, `capacityPerPersonPerDay`, `productivityScore`, `wagePerDay`, `effectiveCapacity`를 가진다.

## Capacity Formula

```text
effectiveCapacity = allocatedHeadcount * capacityPerPersonPerDay * productivityScore
remainingCapacity = effectiveCapacity - usedCapacity
```

workforce allocation이 없으면 API는 0/empty default summary를 반환한다. 기존 event receiver, transaction creation, ledger entry creation, settlement, reconciliation 동작은 유지된다.

## ArchiveOS Summary API

ArchiveOS Workforce Overview는 다음 read-only API를 호출한다.

```text
GET /api/workforce/summary
GET /api/productivity/summary
GET /api/capacity/summary
```

데이터가 없으면 0 또는 empty default summary를 반환한다. 조회 중 settlement, reconciliation, approval callback, fee 생성은 실행하지 않는다.

핵심 계산 기준:

- `approvalBacklog`: `finance_transaction.status = APPROVAL_REQUIRED`
- `settlementBacklog`: `finance_transaction.status = SETTLEMENT_READY`
- `reconciliationBacklog`: latest reconciliation mismatch 또는 failed received event
- `callbackBacklog`: requested approval callback demand
- `bottleneckRole`: approval, settlement, reconciliation, callback backlog 중 가장 큰 병목 role

ArchiveOS가 DEGRADED로 보지 않도록 세 API는 항상 HTTP 200과 `available=true`를 반환한다.

## Guard

- `workdayId + roleType` 중복 allocation은 DB unique index로 방지한다.
- `hopCount > maxHop` allocation은 거부한다.
- workforce 비용 이벤트는 audit/summary로 표현하고 finance transaction으로 재수신하지 않아 fee loop를 만들지 않는다.
