# Archive-Ledger

Synthetic Financial Transaction Processing Backend.

Archive-Ledger는 실제 사용자 금융 데이터 없이 Archive-Nexus의 synthetic manufacturing / mobility domain event를 받아 금융성 거래로 정규화한다. 처리 범위는 거래 생성, double-entry 원장, 승인 대기, 일별 정산, 대사, 감사 로그, 운영 지표다.

## 기술 스택

- Java 21, Spring Boot 3
- Spring Web, Spring Data JPA, JDBC, Spring Batch
- PostgreSQL, Flyway
- Actuator, Prometheus metrics
- Docker / Kubernetes manifests

## 실행

```powershell
docker compose up --build -d
```

| 항목 | 주소 |
| --- | --- |
| Ledger API | `http://localhost:18080` |
| Health | `http://localhost:18080/actuator/health` |
| Prometheus | `http://localhost:18080/actuator/prometheus` |
| PostgreSQL | `localhost:56543` |

## 주요 API

- `POST /api/events/nexus`
- `POST /api/events/nexus/bulk`
- `GET /api/events/received`
- `GET /api/transactions`
- `GET /api/ledger/entries`
- `GET /api/ledger/summary`
- `POST /api/settlements/daily/run?date=YYYY-MM-DD`
- `POST /api/reconciliation/daily?date=YYYY-MM-DD`
- `POST /api/approvals/callback`
- `GET /api/operations/summary`

## 패키지 구조

- `common`: API DTO와 metrics
- `event`: Nexus event ingestion 확장 지점
- `transaction`: transaction normalization 확장 지점
- `ledger`: double-entry ledger 확장 지점
- `settlement`: settlement batch 확장 지점
- `reconciliation`: reconciliation 확장 지점
- `approval`: ArchiveOS external approval client
- `audit`: audit log 확장 지점
- `policy`: synthetic policy rule 확장 지점
- `config`: runtime configuration 확장 지점

## 안전 기준

- 실제 카드번호, 계좌번호, 주민번호, 전화번호, 실사용자 데이터는 사용하지 않는다.
- `syntheticAccountId`, `corporateCardToken`, `vendorId`는 모두 synthetic identifier다.
- 중복 이벤트는 `event_id`와 `idempotency_key` unique 제약으로 방지한다.
- 승인 전 거래는 정산에서 제외한다.
- debit/credit 원장 합계가 맞도록 거래마다 2개 이상의 ledger entry를 생성한다.

## 테스트

```powershell
.\gradlew.bat test --no-daemon --console=plain
```

검증 항목:

- 중복 이벤트 안전 처리
- 고액/위험 거래 승인 대기
- 승인 불필요 거래 정산 준비
- double-entry 균형
- 정산 배치에서 승인 대기 제외
- 승인/반려 callback 상태 전이
- 대사 count 계산
- 잘못된 payload 실패/audit 기록
- bulk 1,000건 처리
