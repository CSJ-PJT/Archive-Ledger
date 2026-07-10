# Archive-Ledger i18n Audit

## Scope

Archive-Ledger process dashboard의 사용자 노출 문구를 i18n key 기반으로 분리했다.

대상 화면:

- `/process.html`
- `/`
- Archive Platform 처리 흐름 카드
- 운영 요약 지표
- source별 처리량 패널
- ledger debit/credit balance 패널
- 최근 transaction 테이블
- 운영 runbook 영역
- 우측 상단 언어 전환기

## Supported Locales

- `ko`: 한국어, 기본값
- `en`: English
- `ja`: 日本語
- `zh-CN`: 简体中文

선택 언어는 `localStorage`의 `archive.locale`에 저장한다. 기존 `archive-ledger-language` 값이 있으면 최초 로드 시 `archive.locale`로 이전한다.

## Implemented Keys

처리한 문구 범위:

- 메뉴/버튼/언어 선택 label
- 카드 제목과 설명
- 테이블 헤더
- loading/empty state
- dashboard metric label
- status label
- approval label
- runbook 문구
- aria-label/title 접근성 문구

## Dynamic Labels

API에서 내려오는 원본 enum/status/eventType 값은 변경하지 않는다. UI 표시용 label만 다음 방식으로 번역한다.

- `APPROVAL_REQUIRED` -> 승인 필요 / Approval required / 承認が必要 / 需要审批
- `SETTLEMENT_READY` -> 정산 대기 / Settlement ready / 精算待ち / 待结算
- `SETTLED` -> 정산 완료 / Settled / 精算済み / 已结算
- `REJECTED` -> 반려 / Rejected / 却下 / 已拒绝
- `BALANCED` -> 균형 / Balanced / 均衡 / 平衡
- `CHECK` -> 확인 필요 / Check / 確認が必要 / 需要检查

상태 원문은 table cell 또는 metric의 `title` 속성에 유지한다.

## Translation Exclusions

다음 값은 시스템 계약 또는 식별자이므로 번역하지 않았다.

- API path: `/api/events/nexus`, `/api/events/logistics`, `/api/transactions`, `/api/ledger/summary`
- eventType: `LOGISTICS_DISPATCHED`, `LOGISTICS_COST_CONFIRMED`
- enum/status raw value: `APPROVAL_REQUIRED`, `SETTLEMENT_READY`, `REJECTED`
- table data identifier: `transactionId`, `transactionType`
- service/repository name: `ArchiveOS`, `Archive-Nexus`, `Archive-Logistics`, `Archive-Ledger`
- compatibility source literal: `Archive-Logitics`
- endpoint query examples and command fragments

## Remaining Hardcoded Text

사용자 화면에서 의도적으로 남긴 하드코딩:

- `ArchiveOS`, `Archive-Nexus`, `Archive-Logistics`, `Archive-Ledger`: 고유명사
- `Archive-Logitics`: 기존 계약 호환 source literal
- `finance_transaction`, `debit`, `credit`, `eventId`, `idempotencyKey`: 시스템/도메인 식별자
- `received - duplicate - created - failed`: reconciliation 계산식 표시
- API path와 status code 예시

개발자 주석, 테스트 fixture, 내부 로그 문자열은 이번 사용자 화면 i18n 범위에서 제외했다.

## Fallback Policy

- 지원하지 않는 locale은 `ko`로 fallback한다.
- 누락 key는 `ko` 번역을 사용한다.
- 그래도 없으면 key 문자열을 그대로 표시하고 `ArchiveLedgerI18n.missingKeys()`에서 확인할 수 있다.

## Future Improvements

- Playwright 기반 screenshot regression 추가
- table filter/search UI가 추가되면 placeholder/toast key 확장
- API errorCode별 프론트 label 매핑 추가
