# Archive-Ledger

<p align="center">
  <img src="docs/brand/archive-os-logo.png" width="260" alt="ArchiveOS" />
</p>

Archive-Ledger는 Archive-Nexus direct 이벤트와 Archive-Logistics의 물류비 확정 이벤트를 수신해 거래 정규화, 복식 원장 기록, 승인 필요 여부 판단, 정산 제외, 대사 집계를 처리하는 Spring Boot 금융 백엔드입니다. source별 이벤트 구분, idempotency key 중복 방지, debit/credit 균형 검증으로 제조 → 물류 → 금융성 정산 흐름을 안정적으로 연결합니다.

## 핵심 역할

- Archive-Nexus 직접 이벤트(예: 제조/물류 직접 이벤트) 수신 및 처리
- Archive-Logistics 물류비 확정 이벤트 수신 및 처리
- eventId/idempotencyKey 기반 duplicate-safe 처리
- finance_transaction 생성 및 원장 분개 생성
- 승인 필요 거래(Approval gate) 관리
- 정산 배치 및 reconciliation 집계
- 감사 로그 기록

## 실행

```powershell
docker compose up --build -d
curl.exe http://localhost:18080/actuator/health
```

| 항목 | 주소 |
| --- | --- |
| API | `http://localhost:18080` |
| Health | `http://localhost:18080/actuator/health` |
| PostgreSQL | `localhost:56543` |

## API

- `POST /api/events/nexus`
- `POST /api/events/nexus/bulk`
- `POST /api/events/logistics`
- `POST /api/events/logistics/bulk`
- `GET /api/events/received` (`source` 지원)
- `GET /api/events/received/{eventId}`
- `GET /api/transactions` (`status`, `source` 지원)
- `GET /api/transactions/{transactionId}`
- `GET /api/ledger/entries` (`transactionId` 지원)
- `GET /api/ledger/summary` (`date`, `factoryId`, `source` 지원)
- `POST /api/settlements/daily/run?date=YYYY-MM-DD`
- `GET /api/settlements`
- `GET /api/settlements/{batchId}`
- `GET /api/settlements/{batchId}/details`
- `POST /api/reconciliation/daily?date=YYYY-MM-DD`
- `GET /api/reconciliation/daily?date=YYYY-MM-DD`
- `GET /api/reconciliation/summary`
- `POST /api/approvals/callback`
- `GET /api/operations/summary`

## 처리 규칙(요약)

- 물류 이벤트 금액은 `totalCost` → `estimatedCost` → `amount` 순으로 사용
- 금액이 없거나 0 이하면 실패 처리
- `APPROVAL_REQUIRED`는 정산 대상에서 제외
- 외부 표기는 `Archive-Logistics`로 통일한다.
- 기존 계약 호환을 위해 이벤트 payload/query의 `source=Archive-Logitics` 값은 계속 수신한다.
- `source=Archive-Logitics` + `LOGISTICS_DISPATCHED`도 호환 모드로 `LOGISTICS_COST` 처리

## 제출 검증 명령

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat bootJar --no-daemon --console=plain
docker compose config --quiet
```

## 문서

- `docs/logistics-event-contract.md`
- `docs/ledger-transaction-mapping.md`
- `docs/demo-ledger-with-logitics.md`
- `docs/final-smoke-result.md`
- `docs/portfolio-bullets.md`
