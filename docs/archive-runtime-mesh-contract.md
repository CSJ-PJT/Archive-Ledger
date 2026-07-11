# Archive Runtime Mesh V1

Archive-Ledger는 ArchiveOS Console V3가 synthetic runtime 상태를 read-only로 수집할 수 있도록 Runtime Mesh V1 계약을 제공한다.

## API

| API | 용도 |
| --- | --- |
| `GET /api/runtime/status` | scheduler, 최신 작업, backlog, cursor 상태 |
| `GET /api/runtime-events/recent?limit=100` | 최신 runtime projection 조회 |
| `GET /api/runtime-events/recent?after={cursor}&limit=100` | 마지막 cursor 이후의 새 projection 조회 |
| `GET /api/runtime-events/correlation/{correlationId}` | 하나의 cross-service 흐름 조회 |
| `GET /api/runtime-events/entity/{entityId}` | synthetic entity 단위 조회 |
| `GET /api/operations/summary` | 거래, 정산, 대사, 경제, workforce 운영 요약 |
| `GET /api/workforce/summary` | workforce allocation과 backlog 요약 |
| `GET /api/productivity/summary` | 처리량과 생산성 요약 |
| `GET /api/capacity/summary` | capacity와 병목 요약 |

모든 `GET` API는 read-only다. insert, seed, simulation 실행, outbox publish, settlement 실행, reconciliation 실행, approval 처리, callback 전송을 수행하지 않는다.

## Runtime Event Envelope

각 projection은 아래 필드를 제공한다. 값이 원본 도메인에 존재하지 않는 내부 projection은 deterministic runtime 값 또는 `null`을 사용하며, eventId와 cursor는 항상 제공한다.

```json
{
  "eventId": "rt-transaction-TRANSACTION_CREATED-...",
  "idempotencyKey": "RUNTIME:rt-transaction-...",
  "sourceService": "Archive-Market",
  "targetService": "Archive-Ledger",
  "domain": "ledger",
  "eventType": "TRANSACTION_CREATED",
  "entityType": "transaction",
  "entityId": "TX-...",
  "correlationId": "CORR-...",
  "causationId": "CAUSE-...",
  "simulationRunId": "SIM-...",
  "settlementCycleId": "CYCLE-...",
  "workdayId": "WORKDAY-...",
  "status": "COMPLETED",
  "severity": "NORMAL",
  "occurredAt": "2026-07-11T00:00:00Z",
  "hopCount": 1,
  "maxHop": 5,
  "cursor": "...",
  "metadata": {}
}
```

`metadata`에는 synthetic ID와 운영 지표만 포함한다. 원본 금액은 노출하지 않고 `amountBucket`, `syntheticKrwRange`으로 축약한다. 실제 이름, 주소, 전화번호, 카드번호, 계좌번호, 결제 token, secret, password, webhook, private key는 허용하지 않는다.

`status`는 `CREATED`, `PROCESSING`, `WAITING`, `COMPLETED`, `DELAYED`, `FAILED`, `SETTLED` 중 하나이며, `severity`는 `NORMAL`, `INFO`, `WARNING`, `CRITICAL` 중 하나다. 승인 대기는 `eventType=APPROVAL_REQUIRED`, `status=WAITING`으로 표현하고, 승인 반려는 `eventType=APPROVAL_REJECTED`, `status=FAILED`로 표현한다.

정산 batch는 `eventType=SETTLEMENT_STARTED`와 `eventType=SETTLEMENT_COMPLETED`로 분리된다. 시작 projection은 `PROCESSING`, 완료 projection은 `SETTLED` 상태를 사용한다.

## Cursor Semantics

`cursor`는 `occurredAt`과 eventId에서 만든 URL-safe 재개 토큰이다. `after`는 해당 cursor보다 새로 발생한 이벤트만 반환한다. 잘못된 cursor는 빈 배열을 반환하며, 서비스 상태를 변경하지 않는다.

Runtime event는 삭제하지 않는다. ArchiveOS가 잠시 중단되어도 Ledger의 수신, 거래 처리, 원장 기록, 정산, 대사 기능은 계속 동작한다.

## No-data and Degraded State

`/api/runtime/status`의 `pipelineStatus=WAITING_FOR_DATA`와 `degradedReason=NO_RUNTIME_DATA`는 아직 수집 가능한 runtime 데이터가 없음을 의미한다. backlog가 존재하면 `pipelineStatus=LIVE_WITH_BACKLOG`, `degradedReason=BACKLOG_PRESENT`를 반환한다. 이는 장애가 아니라 운영 우선순위를 표현하는 상태다.
