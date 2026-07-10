# Ledger Workforce Model

Archive-Ledger의 workforce 모델은 거래 처리, 원장 기록, 정산, 대사, 승인 검토, callback 대응을 synthetic workforce capacity로 계산한다.

실제 회계 담당자 이름, 급여, 개인정보는 사용하지 않는다. 모든 금액은 synthetic KRW다.

## Roles

| Role | 기본 1인 1일 capacity |
| --- | ---: |
| `TRANSACTION_PROCESSOR` | 100 transactions |
| `LEDGER_ACCOUNTANT` | 100 ledger entry operations |
| `SETTLEMENT_OPERATOR` | 120 settlement items |
| `RECONCILIATION_ANALYST` | 20 reconciliation issues |
| `APPROVAL_REVIEWER` | 40 approval requests |
| `CALLBACK_OPERATOR` | 50 callback/retry items |
| `LEDGER_MANAGER` | 200 coordination capacity |

각 allocation은 `allocatedHeadcount`, `capacityPerPersonPerDay`, `productivityScore`, `wagePerDay`, `effectiveCapacity`를 가진다.

## Capacity formula

```text
effectiveCapacity = allocatedHeadcount * capacityPerPersonPerDay * productivityScore
remainingCapacity = effectiveCapacity - usedCapacity
```

workforce allocation이 없으면 baseline capacity로 동작한다. 이 경우 기존 event receiver, transaction creation, ledger entry creation, settlement, reconciliation 동작은 유지된다.

## Guard

- `workdayId + roleType` 중복 allocation은 DB unique index로 방지한다.
- `hopCount > maxHop` allocation은 거부한다.
- workforce 비용 이벤트는 audit/summary로 표현하고 finance transaction으로 재수신하지 않아 fee loop를 만들지 않는다.
