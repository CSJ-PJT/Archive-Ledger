# Continuous Settlement Runtime and Balance Alignment

Archive-Ledger는 Market, Nexus, Logistics의 synthetic 이벤트를 수신 즉시 거래와 복식 원장으로 정규화하고, autonomous runtime tick에서 정산 후속 업무를 제한된 capacity로 진행한다.

## Actual Work

| 단계 | 처리 방식 |
| --- | --- |
| Inbox / transaction / ledger entry | event receiver가 synchronous transaction으로 처리 |
| Approval rule | 이벤트 정규화 시 판단. `APPROVAL_REQUIRED`는 자동 승인하지 않음 |
| Approved follow-up | 승인 callback의 `APPROVED` 결정만 `SETTLEMENT_READY`로 전이 |
| Settlement | tick의 `SETTLEMENT_OPERATOR` capacity 및 `max-backlog-per-tick` 내에서 batch 실행 |
| Reconciliation | tick마다 reconciliation 결과 갱신 |
| Approval dispatch retry | ArchiveOS integration enabled일 때만 failed dispatch를 제한 횟수 재시도 |
| Workforce / backlog | workday 결과, bottleneck, 비용, productivity에 반영 |

callback이 중복 수신되면 transaction의 현재 상태를 유지하고 `duplicate=true`를 반환한다. 이미 `SETTLEMENT_READY`, `SETTLED`, `REJECTED` 상태인 거래를 callback으로 되돌리지 않는다.

## Runtime Events

`SETTLEMENT_STARTED`와 `SETTLEMENT_COMPLETED`는 같은 `settlement_batch` entity에 대해 별도로 projection된다. 나머지 주요 eventType은 `MARKET_REVENUE_RECEIVED`, `NEXUS_COST_RECEIVED`, `LOGISTICS_COST_RECEIVED`, `TRANSACTION_CREATED`, `LEDGER_ENTRY_CREATED`, `APPROVAL_REQUIRED`, `APPROVAL_APPROVED`, `APPROVAL_REJECTED`, `SETTLEMENT_READY`, `RECONCILIATION_OK`, `RECONCILIATION_WARNING`, `CALLBACK_SENT`, `CALLBACK_FAILED`, `WORKDAY_COMPLETED`, `APPROVAL_BACKLOG_INCREASED`, `SETTLEMENT_DELAYED`다.

## Balance and Capacity Read Model

`/api/settlement-agency/summary.balance`와 `/api/operations/summary.balance`는 다음 synthetic 운영 지표를 제공한다.

- `transactionProcessingRevenue`
- `settlementAgencyRevenue`
- `reconciliationRevenue`
- `approvalReviewRevenue`
- `available`: workday snapshot이 존재하는지 여부
- `calculationScope`: 현재 `WORKDAY`
- `calculatedAt`: snapshot 계산 시각
- `workforceCost`
- `callbackFailureCost`
- `operatingCost`
- `operatingProfit`
- `operatingMargin`: `operatingProfit / totalRevenue` 비율. 참고 목표 범위는 `0.04`~`0.12`다.
- `cashBalance`: 전일 synthetic balance에 당일 operating profit을 반영한 값
- `transactionsReceived`, `transactionsProcessed`
- `approvalBacklog`, `settlementBacklog`, `reconciliationBacklog`, `callbackBacklog`
- `capacityUtilization`, `bottleneckRole`, `settlementDelayRate`, `negativeProfitStreak`

일별 snapshot은 `ledger_runtime_balance_snapshot`에 upsert된다. 같은 날짜의 runtime tick은 해당 일자의 값을 재계산하므로 tick마다 payroll이나 cash balance가 중복 누적되지 않는다. `settlementCycleId`는 수신 이벤트에서 보존되고 snapshot에 최근 cycle context로 기록된다.

## Safety

- debit sum과 credit sum은 transaction별로 동일하게 생성된다.
- `eventId`, `idempotencyKey`, Market correlation duplicate guard를 유지한다.
- fee/cost event에는 settlement agency fee를 중복 부과하지 않는다.
- `APPROVAL_REQUIRED`, `REJECTED` 거래는 settlement query에서 제외된다.
- ArchiveOS approval dispatch 재시도는 integration enabled와 retry limit을 모두 만족할 때만 수행한다.
- ArchiveOS down은 audit fallback으로 기록하며 core Ledger transaction을 rollback하지 않는다.
