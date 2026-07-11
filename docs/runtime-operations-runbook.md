# Runtime Operations Runbook

## Runtime Loop

local/demo 환경에서는 제한된 autonomous work loop를 사용할 수 있다.

```env
ARCHIVE_RUNTIME_AUTORUN_ENABLED=true
ARCHIVE_RUNTIME_TICK_INTERVAL=30s
ARCHIVE_RUNTIME_MAX_EVENTS_PER_TICK=10
ARCHIVE_RUNTIME_MAX_BACKLOG_PER_TICK=50
ARCHIVE_RUNTIME_CALLBACK_RETRY_LIMIT=3
```

각 tick은 중복 workday guard와 scheduler lock을 사용한다. Ledger의 실제 synthetic 업무는 workday capacity 계산, reconciliation, 조건을 만족하는 daily settlement batch다. heartbeat만 반복 생성하지 않는다.

## 상태 점검

```powershell
curl.exe http://localhost:18080/actuator/health
curl.exe http://localhost:18080/api/runtime/status
curl.exe http://localhost:18080/api/operations/summary
curl.exe http://localhost:18080/api/workforce/summary
curl.exe http://localhost:18080/api/productivity/summary
curl.exe http://localhost:18080/api/capacity/summary
```

확인 항목:

- `schedulerStatus=RUNNING`
- `pipelineStatus=LIVE` 또는 backlog가 있으면 `LIVE_WITH_BACKLOG`
- `latestCursor`가 runtime event cursor와 일치
- `approvalBacklog`, `settlementBacklog`, `reconciliationBacklog`, `callbackBacklog` 확인
- `bottleneckRole`에 따라 workforce allocation 또는 운영 batch를 조정

## 안전 제한

- tick당 work 수: `max-events-per-tick` 이하
- 한 tick에서 반영하는 backlog: `max-backlog-per-tick` 이하
- settlement는 `SETTLEMENT_OPERATOR` capacity와 backlog limit 중 더 작은 처리량으로 제한
- approval dispatch retry는 ArchiveOS integration enabled 상태에서만 `callback-retry-limit` 이내로 실행
- eventId/idempotencyKey duplicate guard 유지
- hopCount가 maxHop을 넘는 입력 이벤트 거부
- summary GET은 상태 변경 금지
