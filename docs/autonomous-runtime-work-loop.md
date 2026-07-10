# Autonomous Runtime Work Loop

Archive-Ledger는 ArchiveOS Live Flow에서 정체 상태로 보이지 않도록 제한된 속도의 autonomous runtime work loop를 제공한다.

모든 데이터는 Synthetic Runtime Data다. 실제 개인정보, 실제 결제정보, 실제 금융정보, 실제 직원정보는 사용하지 않는다.

## Configuration

```yaml
archive:
  runtime:
    autorun:
      enabled: true
    tick-interval: 30s
    initial-delay: 10s
    max-events-per-tick: 10
    max-backlog-per-tick: 50
```

운영 환경에서 자동 실행을 끄려면 다음 환경변수를 사용한다.

```env
ARCHIVE_RUNTIME_AUTORUN_ENABLED=false
```

## Tick Work

tick은 다음 작업을 제한적으로 수행한다.

1. `ledger_workday_result` 생성으로 capacity/productivity/backlog 상태 갱신
2. `reconciliation_result` 생성으로 대사 상태 갱신
3. backlog가 제한 이하일 때만 daily batch 정산 수행
4. `RUNTIME_WORK_TICK` audit 기록

`GET` summary API는 이 작업을 실행하지 않는다. 자동 작업은 scheduler 또는 명시적 service tick 호출에서만 수행된다.

## Runtime Status API

```text
GET /api/runtime/status
```

응답 필드:

- `service`
- `runtimeActive`
- `autoRunEnabled`
- `schedulerStatus`
- `lastWorkAt`
- `lastEventAt`
- `eventsProducedLastTick`
- `eventsConsumedLastTick`
- `backlogCount`
- `pipelineStatus`

## Safety Guard

- tick별 deterministic `tickId`를 사용한다.
- 같은 `tickId`는 `ledger_workday_result`와 `audit_log` 기준으로 duplicate 처리한다.
- in-memory scheduler lock으로 동시 실행을 막는다.
- `max-events-per-tick`으로 tick당 작업 수를 제한한다.
- `max-backlog-per-tick`으로 대량 backlog 자동 정산을 제한한다.
- 외부 write는 수행하지 않는다. ArchiveOS callback은 기존 approval flow 설정을 따른다.
