# Ledger Productivity Model

Archive-Ledger의 productivity 모델은 synthetic workforce가 실제로 처리 가능한 거래, 정산, 승인, 대사, callback 업무량을 요약한다.

## Demand

```text
transactionsReceived = received_event count
settlementReady = finance_transaction where status = SETTLEMENT_READY
approvalRequired = finance_transaction where status = APPROVAL_REQUIRED
reconciliationIssues = latest mismatch or failed received_event count
callbackDemand = approval_request where status = REQUESTED
callbackFailed = ARCHIVEOS_APPROVAL_DEGRADED audit count
```

## Backlog

```text
transactionsBacklog = transactionsReceived - transactionProcessorCapacity
settlementBacklog = settlementReady - settlementOperatorCapacity
approvalBacklog = approvalRequired - approvalReviewerCapacity
reconciliationBacklog = reconciliationIssues - reconciliationAnalystCapacity
callbackBacklog = callbackDemand - callbackOperatorCapacity
```

## Productivity Score

```text
productivityScore = totalProcessed / totalDemand
```

`totalDemand = 0`이면 `productivityScore = 0.0000`으로 반환한다.

## Bottleneck

가장 큰 backlog를 가진 role을 `bottleneckRole`로 표시한다.

- approval backlog가 가장 크면 `APPROVAL_REVIEWER`
- settlement backlog가 가장 크면 `SETTLEMENT_OPERATOR`
- reconciliation warning이 있으면 `RECONCILIATION_ANALYST`
- callback failure가 있으면 `CALLBACK_OPERATOR`
- 없으면 `NONE`

## Read-only Productivity Summary

`GET /api/productivity/summary`는 ArchiveOS가 Ledger 처리량을 읽는 read-only API다.

주요 필드:

- `productivityScore`
- `transactionsProcessed`
- `approvalReviewed`
- `settlementsCompleted`
- `reconciliationWarnings`
- `callbackFailed`
- `bottleneckRole`

계산 기준:

- `transactionsProcessed`: `finance_transaction` count
- `approvalReviewed`: `approval_request.status in (APPROVED, REJECTED)` count
- `settlementsCompleted`: `finance_transaction.status = SETTLED` count
- `reconciliationWarnings`: `reconciliation_result.status in (WARNING, CRITICAL)` 또는 `mismatch_count > 0`
- `callbackFailed`: `ARCHIVEOS_APPROVAL_DEGRADED` audit count
