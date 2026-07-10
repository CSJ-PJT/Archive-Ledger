# Workforce Settlement Impact

Archive-Ledger는 settlement agency backend이므로 workforce capacity는 수익과 비용 모두에 영향을 준다.

## Revenue impact

| Driver | Revenue |
| --- | ---: |
| processed transaction | synthetic transaction processing fee |
| completed settlement item | synthetic settlement agency fee |
| processed reconciliation issue | synthetic reconciliation verification fee |
| reviewed approval request | synthetic approval review fee |

현재 summary 계산은 다음 단가를 사용한다.

```text
transactionProcessingFee = 120 KRW
settlementAgencyFee = 700 KRW
reconciliationFee = 500 KRW
approvalReviewFee = 900 KRW
```

## Cost impact

| Cost event meaning | Trigger |
| --- | --- |
| `LEDGER_WORKFORCE_PAYROLL_COST_INCURRED` | active workforce payroll exists |
| `SETTLEMENT_BACKLOG_COST_INCURRED` | settlement backlog > 0 |
| `RECONCILIATION_DELAY_COST_INCURRED` | reconciliation backlog > 0 |
| `APPROVAL_BACKLOG_COST_INCURRED` | approval backlog > 0 |
| `CALLBACK_DELAY_COST_INCURRED` | callback backlog > 0 |

Cost events are recorded through audit/summary. They are not recursively emitted as finance transaction events, so fee events do not create another fee loop.

## Settlement rule

`SETTLEMENT_READY` remains the only settlement target.

When `SETTLEMENT_OPERATOR` capacity is lower than settlement-ready demand, the workday result records:

- `settlementCompletedCount`
- `settlementBacklog`
- `bottleneckRole = SETTLEMENT_OPERATOR` when it is the dominant backlog

Existing settlement APIs are preserved. Workforce currently calculates operational capacity/backlog and exposes it to ArchiveOS/Live Flow.
