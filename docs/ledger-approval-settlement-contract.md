# Ledger Approval and Settlement Runtime Contract

Archive-Ledger는 승인 대기, 정산 지연, 대사 경고, callback 실패를 ArchiveOS Live Flow가 읽을 수 있도록 runtime projection으로 노출합니다.

## Approval

| Ledger state | Runtime eventType | displayLabel |
| --- | --- | --- |
| `finance_transaction.status=APPROVAL_REQUIRED` | `APPROVAL_REQUIRED` | `승인 대기 거래 1건` |
| approval callback approved | `APPROVAL_APPROVED` | `승인 완료 거래 1건` |
| approval callback rejected | `APPROVAL_REJECTED` | `승인 반려 거래 1건` |

승인 필요 거래는 정산 대상에서 제외됩니다. `APPROVED` callback 이후에만 `SETTLEMENT_READY`로 전이되고, `REJECTED`는 계속 정산 제외 상태입니다.

## Settlement

| Ledger state | Runtime eventType | 설명 |
| --- | --- | --- |
| `finance_transaction.status=SETTLEMENT_READY` | `SETTLEMENT_READY` | 정산 대기 |
| `settlement_batch.status=SUCCESS` | `SETTLEMENT_COMPLETED` | 일 정산 완료 |
| `ledger_workday_result.settlement_backlog_count > 0` | `SETTLEMENT_DELAYED` | workforce capacity 부족으로 정산 지연 |

정산은 `SETTLEMENT_READY` 거래만 포함합니다. `APPROVAL_REQUIRED`, `REJECTED` 거래는 제외합니다.

## Reconciliation

| Ledger state | Runtime eventType |
| --- | --- |
| `mismatch_count=0` | `RECONCILIATION_OK` |
| `mismatch_count>0` | `RECONCILIATION_WARNING` |

대사는 `received - duplicate - created - failed` 기준으로 mismatch를 계산합니다.

## Callback

| Audit action | Runtime eventType |
| --- | --- |
| `ARCHIVEOS_APPROVAL_REQUESTED` | `CALLBACK_SENT` |
| `ARCHIVEOS_APPROVAL_DEGRADED` | `CALLBACK_FAILED` |

ArchiveOS 장애가 발생해도 Ledger는 죽지 않고, callback 실패는 `CALLBACK_FAILED` projection으로 노출됩니다.

## Workforce Backlog

| Workday result | Runtime eventType |
| --- | --- |
| `approval_backlog_count > 0` | `APPROVAL_BACKLOG_INCREASED` |
| `settlement_backlog_count > 0` | `SETTLEMENT_DELAYED` |
| workday completed | `WORKDAY_COMPLETED` |

모든 인력/처리량 데이터는 synthetic workforce 기준입니다. 실제 직원 이름, 급여, 개인정보는 사용하지 않습니다.

