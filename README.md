# Archive-Ledger

<p align="center">
  <img src="docs/brand/archive-os-logo.png" width="260" alt="ArchiveOS" />
</p>

이벤트 기반 거래 처리와 원장·정산·대사를 담당하는 Spring Boot 금융 백엔드입니다.

Archive-Ledger는 Archive-Nexus direct 비용 이벤트와 Archive-Logistics native 물류비 이벤트를 수신해 `finance_transaction`을 생성하고, debit/credit `ledger_entry`를 기록하며, settlement batch와 reconciliation을 수행합니다. 승인 필요 거래는 정산에서 제외하고 ArchiveOS approval callback을 통해 상태를 전이합니다.

## 운영 역할

- Archive-Nexus direct 비용 이벤트 처리
- Archive-Logistics native 물류비 이벤트 처리
- `finance_transaction` 생성 및 source event 기준 중복 방지
- debit/credit `ledger_entry` 생성 및 금액 균형 보장
- `SETTLEMENT_READY` 거래 대상 settlement batch
- 일별 reconciliation 및 source별 집계
- `APPROVAL_REQUIRED` 거래의 approval callback 처리
- audit log, Actuator health, operations summary 제공

## 주요 API

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/events/nexus/bulk` | Nexus direct 비용 이벤트 bulk 수신 |
| POST | `/api/events/logistics/bulk` | Logistics 물류비 확정 이벤트 bulk 수신 |
| GET | `/api/transactions` | 거래 조회, `status`, `source` 필터 지원 |
| GET | `/api/ledger/entries` | 원장 entry 조회, `transactionId` 필터 지원 |
| GET | `/api/ledger/summary` | debit/credit 요약, `date`, `factoryId`, `source` 필터 지원 |
| POST | `/api/batches/daily/run` | 승인자 기준 일정산/대사 통합 배치 실행 |
| GET | `/api/batches/daily` | 일배치 실행 이력 조회 |
| POST | `/api/settlements/daily/run` | 일별 정산 실행 |
| POST | `/api/reconciliation/daily` | 일별 대사 실행 |
| GET | `/api/reconciliation/summary` | 최신 대사 결과 조회 |
| POST | `/api/approvals/callback` | 승인/반려 callback 수신 |
| GET | `/api/operations/summary` | 운영 요약 조회 |

단건 수신 API인 `/api/events/nexus`, `/api/events/logistics`도 유지합니다.

## Process Dashboard

Archive-Ledger는 전체 처리 흐름을 확인할 수 있는 운영 대시보드를 제공합니다.

- Local URL: `http://localhost:18080/process.html`
- Root URL: `http://localhost:18080/`

대시보드는 기존 API를 호출해 다음 상태를 한 화면에 표시합니다.

- Nexus direct 이벤트 수신량
- Logistics native 이벤트 수신량
- duplicate-safe 처리량
- finance transaction 생성량
- debit/credit ledger balance
- approval required 거래 수
- settlement 결과
- reconciliation `mismatch`와 `status`
- 최근 transaction 목록

## Language Support

Process Dashboard는 우측 상단 지구본 메뉴에서 화면 언어를 전환할 수 있습니다.

- 지원 언어: 한국어, English, 日本語, 简体中文
- 기본 언어: 한국어
- 저장 방식: `localStorage.archive.locale`
- fallback: 지원하지 않는 locale은 `ko`로 처리
- 번역 범위: UI label, 버튼, 카드 제목, 테이블 헤더, empty/loading/status label
- 번역 제외: API path, eventType, enum/status raw value, repository/service name

`ArchiveOS`, `Archive-Nexus`, `Archive-Logistics`, `Archive-Ledger`는 고유명사이므로 번역하지 않습니다. 기존 계약 호환을 위해 payload/query의 `source=Archive-Logitics` literal은 유지될 수 있으며, UI에서는 호환 source로 설명합니다.

## 운영 원칙

### Idempotency

- `received_event.event_id` unique
- `received_event.idempotency_key` unique
- `finance_transaction.source_event_id` unique
- 같은 이벤트 재수신 시 transaction과 ledger entry를 다시 만들지 않습니다.

### Duplicate Safe

중복 이벤트는 실패가 아니라 정상적인 재시도/재전송 흐름으로 취급합니다. 응답은 `DUPLICATE`이며, audit log에 `DUPLICATE_EVENT`를 기록할 수 있습니다.

### Debit / Credit Balance

모든 거래는 debit entry와 credit entry를 함께 생성합니다. transaction 단위로 debit 합계와 credit 합계가 같아야 하며, logistics 비용은 기본적으로 비용 계정 debit, 미지급금 계정 credit으로 매핑됩니다.

### Settlement Exclusion

정산 대상은 `SETTLEMENT_READY`만 포함합니다.

정산 제외 상태:

- `APPROVAL_REQUIRED`
- `REJECTED`
- failed event
- duplicate event

### Reconciliation Mismatch 보정

Duplicate 이벤트는 transaction을 새로 만들지 않는 것이 정상입니다. 따라서 reconciliation mismatch는 duplicate를 제외한 기대 거래 수를 기준으로 계산합니다.

```text
expectedTransactionCount = max(0, received - duplicate)
mismatch = max(0, expectedTransactionCount - created - failed)
status = mismatch == 0 ? OK : WARNING
```

## Archive-Logistics 호환성

외부 문서와 서비스 표기는 `Archive-Logistics`로 통일합니다.

기존 계약 호환을 위해 이벤트 payload/query의 `source=Archive-Logitics` 값은 계속 수신합니다. 신규 외부 표기인 `source=Archive-Logistics`도 같은 logistics source로 처리하며, 두 값 모두 조회 필터와 operations/reconciliation 집계에 포함됩니다. `Archive-Logitics`는 과거 계약의 source literal이며, `LOGISTICS_DISPATCHED` compatibility event도 `LOGISTICS_COST`로 정규화합니다.

Archive-Logistics daily settlement fee 이벤트인 `LOGISTICS_DAILY_SETTLEMENT_FEE_EARNED`도 native logistics API에서 수신합니다. 이 이벤트는 `ledgerFeePaid`를 우선 금액으로 사용하고 `LOGISTICS_SETTLEMENT_EXPENSE / ACCOUNTS_PAYABLE` 원장을 생성합니다.

## 실행 방법

### 로컬 테스트

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat bootJar --no-daemon --console=plain
```

### Docker Compose

```powershell
docker compose up --build -d
curl.exe http://localhost:18080/actuator/health
curl.exe http://localhost:18080/api/operations/summary
```

기본 포트:

- API: `http://localhost:18080`
- PostgreSQL: `localhost:56543`

### Scheduled Settlement / Reconciliation

Docker Compose enables the operational scheduler by default:

```env
ARCHIVE_LEDGER_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_SETTLEMENT_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_RECONCILIATION_SCHEDULER_ENABLED=true
ARCHIVE_LEDGER_SCHEDULER_FIXED_DELAY_MS=60000
ARCHIVE_LEDGER_SCHEDULER_INITIAL_DELAY_MS=15000
```

The scheduled cycle:

- runs the approved daily batch path when the target date has `SETTLEMENT_READY` transactions;
- avoids creating repeated empty settlement batches;
- runs reconciliation periodically so `/api/reconciliation/summary` stays fresh;
- keeps manual APIs available for explicit date-based reruns.

## Smoke Test

```powershell
curl.exe http://localhost:18080/actuator/health
curl.exe http://localhost:18080/api/operations/summary
curl.exe http://localhost:18080/api/reconciliation/summary
```

Logistics bulk smoke:

```powershell
$payload = '{"source":"Archive-Logitics","events":[{"eventId":"evt-logitics-smoke-001","idempotencyKey":"LOGITICS:LOGISTICS_COST_CONFIRMED:ROUTE-SMOKE-001","source":"Archive-Logitics","eventType":"LOGISTICS_COST_CONFIRMED","schemaVersion":1,"occurredAt":"2026-01-15T10:45:00.000Z","payload":{"routePlanId":"ROUTE-SMOKE-001","shipmentId":"SHIP-SMOKE-001","factoryId":"FAC-A","vendorId":"VENDOR-LOGISTICS-01","originCode":"FAC-A","destinationCode":"DC-SEOUL-01","totalCost":93420,"currency":"KRW","riskScore":0.42,"requiresApproval":false,"reason":"Synthetic logistics cost confirmed by Archive-Logistics","delayed":false,"deviated":false}}]}'
curl.exe -X POST "http://localhost:18080/api/events/logistics/bulk" -H "Content-Type: application/json" -d $payload
curl.exe "http://localhost:18080/api/transactions?source=Archive-Logitics"
curl.exe "http://localhost:18080/api/ledger/summary?source=Archive-Logitics"
```

## 운영 Runbook

1. Health 확인: `GET /actuator/health`
2. 운영 요약 확인: `GET /api/operations/summary`
3. 수신 이벤트 확인: `GET /api/events/received?source=Archive-Logitics`
4. 정산 전 승인 필요 거래 확인: `GET /api/transactions?status=APPROVAL_REQUIRED`
5. scheduler가 켜져 있으면 `SETTLEMENT_READY` 거래는 자동 정산 대상이 됩니다.
6. 승인자가 일정산/대사를 함께 실행: `POST /api/batches/daily/run?date=YYYY-MM-DD&approvedBy=operator`
7. 필요 시 개별 정산 재실행: `POST /api/settlements/daily/run?date=YYYY-MM-DD`
8. 필요 시 개별 대사 재실행: `POST /api/reconciliation/daily?date=YYYY-MM-DD`
9. mismatch 발생 시 `docs/reconciliation-fix.md`와 `docs/operations-runbook.md` 기준으로 원인을 분리합니다.

## 문서

- [Architecture](docs/architecture.md)
- [Process Dashboard](docs/process-dashboard.md)
- [API Reference](docs/api-reference.md)
- [Nexus Direct Event Contract](docs/nexus-direct-event-contract.md)
- [Logistics Event Contract](docs/logistics-event-contract.md)
- [Ledger Transaction Mapping](docs/ledger-transaction-mapping.md)
- [Settlement Runbook](docs/settlement-runbook.md)
- [Reconciliation Fix](docs/reconciliation-fix.md)
- [Smoke Test](docs/smoke-test.md)
- [Operations Runbook](docs/operations-runbook.md)
