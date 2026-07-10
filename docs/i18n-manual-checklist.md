# Archive-Ledger i18n Manual Checklist

자동 스크린샷 생성 대신 최종 수동 점검 기준을 문서화한다.

## URL

- `http://localhost:18080/process.html`
- `http://localhost:18080/`

## Common Checks

- 우측 상단 지구본 언어 전환기가 표시된다.
- `한국어`, `English`, `日本語`, `简体中文`을 선택할 수 있다.
- 선택 즉시 dashboard 문구가 변경된다.
- 새로고침 후 선택한 언어가 유지된다.
- `localStorage.archive.locale`에 선택 언어가 저장된다.
- 지원하지 않는 값은 `ko`로 fallback된다.
- 모바일 폭에서도 header/action 영역이 깨지지 않는다.

## Locale Checks

### ko

- 제목: `Archive-Ledger 처리 대시보드`
- 주요 label: `수신 이벤트`, `거래`, `대사`, `불일치`
- status label: `정상`, `승인 필요`, `정산 대기`

### en

- 제목: `Archive-Ledger Process Dashboard`
- 주요 label: `Received events`, `Transactions`, `Reconciliation`, `Mismatch`
- status label: `Healthy`, `Approval required`, `Settlement ready`

### ja

- 제목: `Archive-Ledger 処理ダッシュボード`
- 주요 label: `受信イベント`, `取引`, `照合`, `不一致`
- status label: `正常`, `承認が必要`, `精算待ち`

### zh-CN

- 제목: `Archive-Ledger 处理看板`
- 주요 label: `接收事件`, `交易`, `对账`, `差异`
- status label: `正常`, `需要审批`, `待结算`

## Exclusion Checks

다음 원본 값은 번역하지 않고 유지한다.

- `ArchiveOS`
- `Archive-Nexus`
- `Archive-Logistics`
- `Archive-Ledger`
- `Archive-Logitics`
- `LOGISTICS_DISPATCHED`
- `APPROVAL_REQUIRED`
- `SETTLEMENT_READY`
- API path
