# ArchiveOS Realtime Integration

## 기본 수집 방식: Pull

ArchiveOS Console V3는 Ledger의 read-only API를 polling 또는 SSE mesh 수집기에 연결한다.

```text
1. GET /api/runtime/status
2. latestCursor를 저장
3. GET /api/runtime-events/recent?after={latestCursor}&limit=100
4. cursor 순서대로 Live Flow projection 반영
5. 응답의 가장 최신 cursor를 다음 수집 기준으로 저장
```

초기 수집은 `GET /api/runtime-events/recent?limit=100`으로 시작한다. correlation 또는 entity 흐름이 필요한 경우 전용 조회 API를 사용한다.

## Push 상태

Ledger에는 현재 ArchiveOS Live Flow ingest의 인증, 재시도 응답 코드, payload version이 검증된 설정으로 제공되지 않았다. 따라서 이 저장소는 임의 인증 header나 미확인 HTTP push를 만들지 않는다.

향후 `POST /api/live-flow/events/ingest`의 최종 계약이 제공되면 다음 원칙으로 확장한다.

- integration enabled일 때만 전송
- eventId 기반 재전송 안전성
- local outbox, exponential backoff, 최대 retry 적용
- push 실패가 Ledger transaction을 rollback하지 않음
- ArchiveOS down 상태에서도 Ledger는 정상 동작

현재는 pull API가 이 요구사항의 장애 격리 경로를 제공한다.

## 확인 명령

```powershell
curl.exe http://localhost:18080/api/runtime/status
curl.exe "http://localhost:18080/api/runtime-events/recent?limit=20"
curl.exe http://localhost:18080/api/operations/summary
```
