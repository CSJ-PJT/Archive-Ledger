# Workforce Settlement Impact

Archive-Ledger의 settlement agency 모델은 workforce capacity와 backlog를 수익/비용 요약에 반영한다.

## Cost Events

| Event | Condition |
| --- | --- |
| `LEDGER_WORKFORCE_PAYROLL_COST_INCURRED` | active workforce payroll exists |
| `SETTLEMENT_BACKLOG_COST_INCURRED` | settlement backlog > 0 |
| `RECONCILIATION_DELAY_COST_INCURRED` | reconciliation backlog > 0 |
| `APPROVAL_BACKLOG_COST_INCURRED` | approval backlog > 0 |
| `CALLBACK_DELAY_COST_INCURRED` | callback backlog > 0 |

Cost events are recorded through audit/summary. They are not recursively emitted as finance transaction events, so fee events do not create another fee loop.

## Settlement Rule

`SETTLEMENT_READY` remains the only settlement target.

When `SETTLEMENT_OPERATOR` capacity is lower than settlement-ready demand, the workday result records:

- `settlementCompletedCount`
- `settlementBacklog`
- `bottleneckRole = SETTLEMENT_OPERATOR` when it is the dominant backlog

Existing settlement APIs are preserved. Workforce currently calculates operational capacity/backlog and exposes it to ArchiveOS/Live Flow.

## Capacity Summary API

`GET /api/capacity/summary`는 ArchiveOS가 정산 처리 capacity와 병목을 읽기 위한 read-only API다.

주요 필드:

- `effectiveCapacity`
- `usedCapacity`
- `remainingCapacity`
- `capacityUtilizationRate`
- `approvalBacklog`
- `settlementBacklog`
- `reconciliationBacklog`
- `callbackBacklog`
- `bottleneckRole`

workforce allocation이 없으면 `effectiveCapacity=0`, `usedCapacity=0`, `remainingCapacity=0`으로 반환하고, 실제 backlog는 transaction/approval/settlement/reconciliation 테이블에서 계산한다.
