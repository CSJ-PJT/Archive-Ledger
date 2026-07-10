# Ledger Productivity Model

Ledger productivity는 workday 단위 처리량과 backlog를 기준으로 계산한다.

## Demand

```text
transactionsReceived = received_event count by received_at date
settlementReadyCount = SETTLEMENT_READY transaction count by occurred_at date
approvalRequiredCount = APPROVAL_REQUIRED transaction count by created_at date
reconciliationIssues = latest mismatch_count or failed received_event count
callbackDemand = REQUESTED approval_request count
```

## Processing

```text
transactionsProcessed = min(transactionsReceived, TRANSACTION_PROCESSOR capacity, LEDGER_ACCOUNTANT capacity)
settlementCompleted = min(settlementReadyCount, SETTLEMENT_OPERATOR capacity)
approvalReviewed = min(approvalRequiredCount, APPROVAL_REVIEWER capacity)
reconciliationProcessed = min(reconciliationIssues, RECONCILIATION_ANALYST capacity)
callbackProcessed = min(callbackDemand, CALLBACK_OPERATOR capacity)
```

## Backlog

```text
transactionsBacklog = transactionsReceived - transactionsProcessed
settlementBacklog = settlementReadyCount - settlementCompleted
approvalBacklog = approvalRequiredCount - approvalReviewed
reconciliationBacklog = reconciliationIssues - reconciliationProcessed
callbackBacklog = callbackDemand - callbackProcessed
```

## Productivity score

```text
productivityScore = totalProcessed / totalDemand
```

`totalDemand = 0`이면 `productivityScore = 1.0000`으로 처리한다.

## Bottleneck

가장 큰 backlog를 가진 role을 `bottleneckRole`로 표시한다.

예:

- settlement backlog가 가장 크면 `SETTLEMENT_OPERATOR`
- approval backlog가 가장 크면 `APPROVAL_REVIEWER`
