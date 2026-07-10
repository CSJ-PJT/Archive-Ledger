# Operations Summary Contract

`GET /api/operations/summary`는 ArchiveOS Operational Twin이 Archive-Ledger의 현재 운영 상태를 read-only로 수집하기 위한 요약 API입니다.

## 주요 필드

| Field | 설명 |
| --- | --- |
| `serviceName` | `Archive-Ledger` |
| `serviceRole` | 금융 거래, 원장, 정산, 대사, 승인 callback, workforce capacity 서비스 설명 |
| `status` | `HEALTHY` 또는 `DEGRADED` |
| `latestEventAt` | 마지막 수신 event timestamp |
| `liveFlowAvailable` | Live Flow 조회 가능 여부 |
| `degradedReason` | degraded 사유. 실패 이벤트가 있으면 `FAILED_EVENTS_PRESENT` |
| `outbox` | 공통 계약용 outbox 카운트. Ledger는 외부 outbox가 없어 0 |
| `economy` | settlement agency revenue/cost/profit |
| `runtimeWorkforce` | synthetic workforce headcount/capacity/used/backlog |
| `workforce` | 기존 workforce 상세 summary |

## Example

```json
{
  "serviceName": "Archive-Ledger",
  "serviceRole": "Synthetic financial ledger, settlement, reconciliation, approval callback, and workforce capacity service",
  "status": "HEALTHY",
  "latestEventAt": "2026-01-15T10:45:00Z",
  "outbox": {
    "pending": 0,
    "published": 0,
    "failed": 0,
    "retry": 0
  },
  "economy": {
    "revenue": 150000,
    "cost": 50000,
    "profit": 100000
  },
  "runtimeWorkforce": {
    "totalHeadcount": 3,
    "effectiveCapacity": 620,
    "usedCapacity": 120,
    "backlog": 0
  },
  "degradedReason": null,
  "liveFlowAvailable": true
}
```

기존 카운트 필드(`receivedEvents`, `transactions`, `duplicates`, `eventsReceivedFromMarket` 등)는 유지하며, ArchiveOS용 필드는 추가 방식으로 확장합니다.

