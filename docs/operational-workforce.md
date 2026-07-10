# Operational Workforce

Archive-Ledger의 Operational Workforce 기능은 정산, 대사, 승인 검토 업무를 synthetic workforce 배정량에 따라 capacity, backlog, 지연, 비용, productivity로 계산한다.

실제 직원 이름, 급여, 개인정보는 사용하지 않는다. 모든 비용은 synthetic KRW 기준이다.

## API

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/workforce/summary` | Workforce 배정, capacity, backlog 요약 |
| GET | `/api/productivity/summary` | Productivity 중심 요약. 현재는 workforce summary와 동일한 계산 결과를 반환 |
| GET | `/api/capacity/summary` | Capacity 중심 요약. 현재는 workforce summary와 동일한 계산 결과를 반환 |
| POST | `/api/workforce/allocations` | Synthetic workforce 배정 |
| POST | `/api/workforce/workday/run?date=YYYY-MM-DD` | workday capacity/backlog/productivity 계산 |

## 지원 sourceService

MVP에서는 배정 주체로 다음 값을 허용한다.

- `ArchiveOS`
- `Archive-Market`

최종 orchestration은 ArchiveOS가 담당하도록 확장 가능하게 두었다.

## 역할별 capacity

| role | unit capacity |
| --- | ---: |
| `SETTLEMENT_OPERATOR` | 120 |
| `RECONCILIATION_ANALYST` | 300 |
| `APPROVAL_REVIEWER` | 40 |
| `LEDGER_OPERATOR` | 100 |
| other synthetic roles | 80 |

기본 baseline capacity는 500이다. workforce allocation이 없으면 baseline capacity만 사용한다.

## 계산 방식

```text
allocatedCapacity = baselineCapacity + sum(assignedUnits * roleCapacity * productivityMultiplier)
demandCount = settlementReady(date) + approvalRequired(createdDate) + receivedEvents(receivedDate)
processedCount = min(demandCount, allocatedCapacity)
backlogCount = max(0, demandCount - processedCount)
delayedCount = backlogCount
productivityScore = processedCount / demandCount
```

`demandCount = 0`이면 `productivityScore = 1.0000`으로 처리한다.

## 이벤트 의미

현재 MVP는 이벤트 타입을 별도 inbound event receiver로 추가하지 않고, audit log action과 workday status로 표현한다.

- `WORKFORCE_ALLOCATION_ASSIGNED`
- `WORKDAY_COMPLETED`
- `BOTTLENECK_DETECTED`

향후 ArchiveOS orchestration에서 다음 공통 이벤트를 직접 수신하도록 확장할 수 있다.

- `WORKDAY_STARTED`
- `PRODUCTIVITY_REPORTED`
- `CAPACITY_SHORTAGE_DETECTED`
- `BACKLOG_INCREASED`

## 안전장치

- `hopCount > maxHop`이면 allocation 요청을 거부한다.
- allocation에는 `simulationRunId`, `settlementCycleId`, `correlationId`, `causationId`, `hopCount`, `maxHop`을 보존한다.
- 기존 event ingestion, settlement, reconciliation API 계약은 변경하지 않는다.

## 예시

```powershell
curl.exe "http://localhost:18080/api/workforce/summary?date=2026-07-10&sourceService=ArchiveOS"

curl.exe -X POST "http://localhost:18080/api/workforce/allocations" `
  -H "Content-Type: application/json" `
  -d "{\"workdayId\":\"LEDGER-WORKDAY-20260710\",\"workDate\":\"2026-07-10\",\"sourceService\":\"ArchiveOS\",\"role\":\"SETTLEMENT_OPERATOR\",\"assignedUnits\":2,\"unitCostKrw\":120000,\"productivityMultiplier\":1.0,\"enabled\":true,\"simulationRunId\":\"SIM-WF-001\",\"settlementCycleId\":\"CYCLE-WF-001\",\"correlationId\":\"CORR-WF-001\",\"causationId\":\"CAUSE-WF-001\",\"hopCount\":1,\"maxHop\":8}"

curl.exe -X POST "http://localhost:18080/api/workforce/workday/run?date=2026-07-10&sourceService=ArchiveOS"
```
