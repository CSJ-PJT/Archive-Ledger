(function () {
  const defaultLocale = "ko";
  const storageKey = "archive.locale";
  const legacyStorageKey = "archive-ledger-language";
  const supportedLocales = ["ko", "en", "ja", "zh-CN"];

  const localeNames = {
    ko: "한국어",
    en: "English",
    ja: "日本語",
    "zh-CN": "简体中文"
  };

  const dictionaries = {
    ko: {
      "brand.eyebrow": "Archive Platform Ecosystem",
      "brand.title": "Archive-Ledger 처리 대시보드",
      "common.language": "언어 선택",
      "common.languageTooltip": "화면 언어 전환",
      "common.loading": "불러오는 중...",
      "common.refresh": "새로고침",
      "common.unavailable": "사용 불가",
      "common.updatedAt": "{time} 갱신",
      "summary.aria": "운영 요약 지표",
      "metrics.service": "서비스",
      "metrics.receivedEvents": "수신 이벤트",
      "metrics.transactions": "거래",
      "metrics.reconciliation": "대사",
      "metrics.mismatch": "불일치",
      "flow.aria": "Archive-Ledger 전체 처리 흐름",
      "flow.eyebrow": "End-to-end flow",
      "flow.title": "제조·물류 이벤트에서 원장·정산·대사까지",
      "flow.nexus.description": "Direct 비용 이벤트를 Ledger로 발행합니다.",
      "flow.nexus.metric": "Nexus 수신 이벤트",
      "flow.logistics.description": "물류비 확정 이벤트를 native API로 전달합니다.",
      "flow.logistics.metric": "호환 source: Archive-Logitics",
      "flow.ingestion.title": "수신 처리",
      "flow.ingestion.description": "eventId와 idempotencyKey로 중복을 차단합니다.",
      "flow.ingestion.metric": "중복 안전 처리",
      "flow.transaction.title": "거래 정규화",
      "flow.transaction.description": "비즈니스 이벤트를 finance_transaction으로 정규화합니다.",
      "flow.transaction.metric": "생성 거래",
      "flow.ledger.title": "복식 원장",
      "flow.ledger.description": "debit/credit entry를 함께 생성해 금액 균형을 유지합니다.",
      "flow.ledger.metric": "원장 균형",
      "flow.approval.title": "승인 게이트",
      "flow.approval.description": "고액·고위험 거래는 ArchiveOS 승인 전 정산에서 제외합니다.",
      "flow.approval.metric": "승인 필요",
      "flow.settlement.title": "정산",
      "flow.settlement.description": "SETTLEMENT_READY 거래만 일별 settlement batch에 포함합니다.",
      "flow.settlement.metric": "정산 완료 거래",
      "flow.reconciliation.title": "대사",
      "flow.reconciliation.description": "duplicate를 제외한 기대 거래 수로 mismatch를 계산합니다.",
      "flow.reconciliation.metric": "received - duplicate - created - failed",
      "details.aria": "상세 운영 패널",
      "source.eyebrow": "Source breakdown",
      "source.title": "이벤트 source별 처리량",
      "source.nexus": "Nexus direct",
      "source.logistics": "Logistics native",
      "source.failed": "실패 이벤트",
      "source.settlementReady": "정산 대기",
      "balance.eyebrow": "원장 균형 검증",
      "balance.title": "이벤트 소스별 차변 / 대변 합계",

      "balance.help": "서비스에서 들어온 거래를 이벤트 소스별로 나누어 차변 합계와 대변 합계가 같은지 확인합니다.",
      "balance.source.logistics": "Archive-Logistics 이벤트",
      "balance.source.nexus": "Archive-Nexus 이벤트",
      "transactions.eyebrow": "Recent transactions",
      "transactions.title": "최근 거래",
      "transactions.empty": "거래가 없습니다.",
      "transactions.table.transaction": "거래",
      "transactions.table.type": "유형",
      "transactions.table.status": "상태",
      "transactions.table.amount": "금액",
      "transactions.table.approval": "승인",
      "runbook.aria": "운영 확인 순서",
      "runbook.eyebrow": "Runbook",
      "runbook.title": "운영 확인 순서",
      "runbook.health": "<code>/actuator/health</code>에서 서비스 상태를 확인합니다.",
      "runbook.operations": "<code>/api/operations/summary</code>에서 event, transaction, approval, settlement 상태를 확인합니다.",
      "runbook.balance": "<code>/api/ledger/summary?source=Archive-Logitics</code>에서 debit/credit 균형을 확인합니다.",
      "runbook.reconciliation": "<code>/api/reconciliation/summary</code>에서 <code>mismatch=0</code>, <code>status=OK</code>를 확인합니다.",
      "ledger.debitShort": "차변 합계",
      "ledger.creditShort": "대변 합계",
      "ledger.entries": "건",
      "approval.required": "승인 필요",
      "approval.notRequired": "승인 불필요",
      "status.UNKNOWN": "알 수 없음",
      "status.UNAVAILABLE": "사용 불가",
      "status.HEALTHY": "정상",
      "status.UP": "정상",
      "status.OK": "정상",
      "status.WARNING": "주의",
      "status.DEGRADED": "저하",
      "status.BALANCED": "균형",
      "status.CHECK": "확인 필요",
      "status.APPROVAL_REQUIRED": "승인 필요",
      "status.SETTLEMENT_READY": "정산 대기",
      "status.SETTLED": "정산 완료",
      "status.REJECTED": "반려",
      "status.FAILED": "실패",
      "status.PENDING": "대기",
      "status.PUBLISHED": "발행 완료",
      "status.RETRY": "재시도",
      "status.DUPLICATE": "중복"
    },
    en: {
      "brand.eyebrow": "Archive Platform Ecosystem",
      "brand.title": "Archive-Ledger Process Dashboard",
      "common.language": "Select language",
      "common.languageTooltip": "Change display language",
      "common.loading": "Loading...",
      "common.refresh": "Refresh",
      "common.unavailable": "Unavailable",
      "common.updatedAt": "Updated {time}",
      "summary.aria": "Operations summary metrics",
      "metrics.service": "Service",
      "metrics.receivedEvents": "Received events",
      "metrics.transactions": "Transactions",
      "metrics.reconciliation": "Reconciliation",
      "metrics.mismatch": "Mismatch",
      "flow.aria": "Archive-Ledger end-to-end processing flow",
      "flow.eyebrow": "End-to-end flow",
      "flow.title": "From manufacturing and logistics events to ledger, settlement, and reconciliation",
      "flow.nexus.description": "Publishes direct cost events to Ledger.",
      "flow.nexus.metric": "Nexus received events",
      "flow.logistics.description": "Sends confirmed logistics cost events through the native API.",
      "flow.logistics.metric": "Compatible source: Archive-Logitics",
      "flow.ingestion.title": "Ingestion",
      "flow.ingestion.description": "Blocks duplicates with eventId and idempotencyKey.",
      "flow.ingestion.metric": "Duplicate-safe handling",
      "flow.transaction.title": "Transaction normalization",
      "flow.transaction.description": "Normalizes business events into finance_transaction rows.",
      "flow.transaction.metric": "Created transactions",
      "flow.ledger.title": "Double-entry ledger",
      "flow.ledger.description": "Creates debit and credit entries together to keep amounts balanced.",
      "flow.ledger.metric": "Ledger balance",
      "flow.approval.title": "Approval gate",
      "flow.approval.description": "High-value or high-risk transactions are excluded from settlement before ArchiveOS approval.",
      "flow.approval.metric": "Approval required",
      "flow.settlement.title": "Settlement",
      "flow.settlement.description": "Only SETTLEMENT_READY transactions are included in the daily settlement batch.",
      "flow.settlement.metric": "Settled transactions",
      "flow.reconciliation.title": "Reconciliation",
      "flow.reconciliation.description": "Calculates mismatch from expected transactions after excluding duplicates.",
      "flow.reconciliation.metric": "received - duplicate - created - failed",
      "details.aria": "Detailed operations panels",
      "source.eyebrow": "Source breakdown",
      "source.title": "Processing volume by event source",
      "source.nexus": "Nexus direct",
      "source.logistics": "Logistics native",
      "source.failed": "Failed events",
      "source.settlementReady": "Settlement ready",
      "balance.eyebrow": "Ledger balance check",
      "balance.title": "Debit / credit totals by event source",

      "balance.help": "Checks whether debit and credit totals match for transactions grouped by the service that produced the event.",
      "balance.source.logistics": "Archive-Logistics events",
      "balance.source.nexus": "Archive-Nexus events",
      "transactions.eyebrow": "Recent transactions",
      "transactions.title": "Recent transactions",
      "transactions.empty": "No transactions.",
      "transactions.table.transaction": "Transaction",
      "transactions.table.type": "Type",
      "transactions.table.status": "Status",
      "transactions.table.amount": "Amount",
      "transactions.table.approval": "Approval",
      "runbook.aria": "Operational check order",
      "runbook.eyebrow": "Runbook",
      "runbook.title": "Operational check order",
      "runbook.health": "Check service status at <code>/actuator/health</code>.",
      "runbook.operations": "Check event, transaction, approval, and settlement status at <code>/api/operations/summary</code>.",
      "runbook.balance": "Check debit/credit balance at <code>/api/ledger/summary?source=Archive-Logitics</code>.",
      "runbook.reconciliation": "Check <code>mismatch=0</code> and <code>status=OK</code> at <code>/api/reconciliation/summary</code>.",
      "ledger.debitShort": "Debit total",
      "ledger.creditShort": "Credit total",
      "ledger.entries": "entries",
      "approval.required": "Approval required",
      "approval.notRequired": "Not required",
      "status.UNKNOWN": "Unknown",
      "status.UNAVAILABLE": "Unavailable",
      "status.HEALTHY": "Healthy",
      "status.UP": "Up",
      "status.OK": "OK",
      "status.WARNING": "Warning",
      "status.DEGRADED": "Degraded",
      "status.BALANCED": "Balanced",
      "status.CHECK": "Check",
      "status.APPROVAL_REQUIRED": "Approval required",
      "status.SETTLEMENT_READY": "Settlement ready",
      "status.SETTLED": "Settled",
      "status.REJECTED": "Rejected",
      "status.FAILED": "Failed",
      "status.PENDING": "Pending",
      "status.PUBLISHED": "Published",
      "status.RETRY": "Retry",
      "status.DUPLICATE": "Duplicate"
    },
    ja: {
      "brand.eyebrow": "Archive Platform Ecosystem",
      "brand.title": "Archive-Ledger 処理ダッシュボード",
      "common.language": "言語を選択",
      "common.languageTooltip": "表示言語を切り替え",
      "common.loading": "読み込み中...",
      "common.refresh": "更新",
      "common.unavailable": "利用不可",
      "common.updatedAt": "{time} 更新",
      "summary.aria": "運用サマリー指標",
      "metrics.service": "サービス",
      "metrics.receivedEvents": "受信イベント",
      "metrics.transactions": "取引",
      "metrics.reconciliation": "照合",
      "metrics.mismatch": "不一致",
      "flow.aria": "Archive-Ledger のエンドツーエンド処理フロー",
      "flow.eyebrow": "End-to-end flow",
      "flow.title": "製造・物流イベントから元帳・精算・照合まで",
      "flow.nexus.description": "Direct コストイベントを Ledger に発行します。",
      "flow.nexus.metric": "Nexus 受信イベント",
      "flow.logistics.description": "確定した物流費イベントを native API で連携します。",
      "flow.logistics.metric": "互換 source: Archive-Logitics",
      "flow.ingestion.title": "受信処理",
      "flow.ingestion.description": "eventId と idempotencyKey で重複を防ぎます。",
      "flow.ingestion.metric": "重複安全処理",
      "flow.transaction.title": "取引正規化",
      "flow.transaction.description": "業務イベントを finance_transaction に正規化します。",
      "flow.transaction.metric": "作成取引",
      "flow.ledger.title": "複式元帳",
      "flow.ledger.description": "debit/credit entry を同時に作成し、金額の均衡を保ちます。",
      "flow.ledger.metric": "元帳バランス",
      "flow.approval.title": "承認ゲート",
      "flow.approval.description": "高額・高リスク取引は ArchiveOS 承認前に精算から除外します。",
      "flow.approval.metric": "承認が必要",
      "flow.settlement.title": "精算",
      "flow.settlement.description": "SETTLEMENT_READY 取引のみ日次 settlement batch に含めます。",
      "flow.settlement.metric": "精算済み取引",
      "flow.reconciliation.title": "照合",
      "flow.reconciliation.description": "duplicate を除外した期待取引数で mismatch を計算します。",
      "flow.reconciliation.metric": "received - duplicate - created - failed",
      "details.aria": "詳細運用パネル",
      "source.eyebrow": "Source breakdown",
      "source.title": "イベント source 別処理量",
      "source.nexus": "Nexus direct",
      "source.logistics": "Logistics native",
      "source.failed": "失敗イベント",
      "source.settlementReady": "精算待ち",
      "balance.eyebrow": "元帳バランス確認",
      "balance.title": "イベントソース別の借方 / 貸方合計",

      "balance.help": "イベントを発生させたサービス別に取引を集計し、借方合計と貸方合計が一致するか確認します。",
      "balance.source.logistics": "Archive-Logistics イベント",
      "balance.source.nexus": "Archive-Nexus イベント",
      "transactions.eyebrow": "Recent transactions",
      "transactions.title": "最近の取引",
      "transactions.empty": "取引はありません。",
      "transactions.table.transaction": "取引",
      "transactions.table.type": "種別",
      "transactions.table.status": "状態",
      "transactions.table.amount": "金額",
      "transactions.table.approval": "承認",
      "runbook.aria": "運用確認手順",
      "runbook.eyebrow": "Runbook",
      "runbook.title": "運用確認手順",
      "runbook.health": "<code>/actuator/health</code> でサービス状態を確認します。",
      "runbook.operations": "<code>/api/operations/summary</code> で event, transaction, approval, settlement 状態を確認します。",
      "runbook.balance": "<code>/api/ledger/summary?source=Archive-Logitics</code> で debit/credit バランスを確認します。",
      "runbook.reconciliation": "<code>/api/reconciliation/summary</code> で <code>mismatch=0</code>, <code>status=OK</code> を確認します。",
      "ledger.debitShort": "借方合計",
      "ledger.creditShort": "貸方合計",
      "ledger.entries": "件",
      "approval.required": "承認が必要",
      "approval.notRequired": "承認不要",
      "status.UNKNOWN": "不明",
      "status.UNAVAILABLE": "利用不可",
      "status.HEALTHY": "正常",
      "status.UP": "正常",
      "status.OK": "正常",
      "status.WARNING": "注意",
      "status.DEGRADED": "低下",
      "status.BALANCED": "均衡",
      "status.CHECK": "確認が必要",
      "status.APPROVAL_REQUIRED": "承認が必要",
      "status.SETTLEMENT_READY": "精算待ち",
      "status.SETTLED": "精算済み",
      "status.REJECTED": "却下",
      "status.FAILED": "失敗",
      "status.PENDING": "待機",
      "status.PUBLISHED": "発行済み",
      "status.RETRY": "再試行",
      "status.DUPLICATE": "重複"
    },
    "zh-CN": {
      "brand.eyebrow": "Archive Platform Ecosystem",
      "brand.title": "Archive-Ledger 处理看板",
      "common.language": "选择语言",
      "common.languageTooltip": "切换显示语言",
      "common.loading": "加载中...",
      "common.refresh": "刷新",
      "common.unavailable": "不可用",
      "common.updatedAt": "{time} 已更新",
      "summary.aria": "运维摘要指标",
      "metrics.service": "服务",
      "metrics.receivedEvents": "接收事件",
      "metrics.transactions": "交易",
      "metrics.reconciliation": "对账",
      "metrics.mismatch": "差异",
      "flow.aria": "Archive-Ledger 端到端处理流程",
      "flow.eyebrow": "End-to-end flow",
      "flow.title": "从制造与物流事件到总账、结算和对账",
      "flow.nexus.description": "向 Ledger 发布 direct 成本事件。",
      "flow.nexus.metric": "Nexus 接收事件",
      "flow.logistics.description": "通过 native API 传递已确认的物流成本事件。",
      "flow.logistics.metric": "兼容 source: Archive-Logitics",
      "flow.ingestion.title": "接收处理",
      "flow.ingestion.description": "通过 eventId 和 idempotencyKey 阻止重复。",
      "flow.ingestion.metric": "重复安全处理",
      "flow.transaction.title": "交易标准化",
      "flow.transaction.description": "将业务事件标准化为 finance_transaction。",
      "flow.transaction.metric": "已创建交易",
      "flow.ledger.title": "复式总账",
      "flow.ledger.description": "同时创建 debit/credit entry，保持金额平衡。",
      "flow.ledger.metric": "总账平衡",
      "flow.approval.title": "审批网关",
      "flow.approval.description": "高额或高风险交易在 ArchiveOS 审批前排除出结算。",
      "flow.approval.metric": "需要审批",
      "flow.settlement.title": "结算",
      "flow.settlement.description": "只有 SETTLEMENT_READY 交易会进入每日 settlement batch。",
      "flow.settlement.metric": "已结算交易",
      "flow.reconciliation.title": "对账",
      "flow.reconciliation.description": "排除 duplicate 后，根据预期交易数计算 mismatch。",
      "flow.reconciliation.metric": "received - duplicate - created - failed",
      "details.aria": "详细运维面板",
      "source.eyebrow": "Source breakdown",
      "source.title": "按事件 source 的处理量",
      "source.nexus": "Nexus direct",
      "source.logistics": "Logistics native",
      "source.failed": "失败事件",
      "source.settlementReady": "待结算",
      "balance.eyebrow": "总账平衡检查",
      "balance.title": "按事件来源的借方 / 贷方合计",

      "balance.help": "按事件来源服务汇总交易，检查借方合计与贷方合计是否一致。",
      "balance.source.logistics": "Archive-Logistics 事件",
      "balance.source.nexus": "Archive-Nexus 事件",
      "transactions.eyebrow": "Recent transactions",
      "transactions.title": "最近交易",
      "transactions.empty": "暂无交易。",
      "transactions.table.transaction": "交易",
      "transactions.table.type": "类型",
      "transactions.table.status": "状态",
      "transactions.table.amount": "金额",
      "transactions.table.approval": "审批",
      "runbook.aria": "运维检查顺序",
      "runbook.eyebrow": "Runbook",
      "runbook.title": "运维检查顺序",
      "runbook.health": "在 <code>/actuator/health</code> 检查服务状态。",
      "runbook.operations": "在 <code>/api/operations/summary</code> 检查 event、transaction、approval、settlement 状态。",
      "runbook.balance": "在 <code>/api/ledger/summary?source=Archive-Logitics</code> 检查 debit/credit 平衡。",
      "runbook.reconciliation": "在 <code>/api/reconciliation/summary</code> 检查 <code>mismatch=0</code> 和 <code>status=OK</code>。",
      "ledger.debitShort": "借方合计",
      "ledger.creditShort": "贷方合计",
      "ledger.entries": "条",
      "approval.required": "需要审批",
      "approval.notRequired": "无需审批",
      "status.UNKNOWN": "未知",
      "status.UNAVAILABLE": "不可用",
      "status.HEALTHY": "正常",
      "status.UP": "正常",
      "status.OK": "正常",
      "status.WARNING": "注意",
      "status.DEGRADED": "降级",
      "status.BALANCED": "平衡",
      "status.CHECK": "需要检查",
      "status.APPROVAL_REQUIRED": "需要审批",
      "status.SETTLEMENT_READY": "待结算",
      "status.SETTLED": "已结算",
      "status.REJECTED": "已拒绝",
      "status.FAILED": "失败",
      "status.PENDING": "待处理",
      "status.PUBLISHED": "已发布",
      "status.RETRY": "重试",
      "status.DUPLICATE": "重复"
    }
  };

  dictionaries.en = { ...dictionaries.ko, ...dictionaries.en };

  function normalizeLocale(locale) {
    if (locale === "zh") return "zh-CN";
    return supportedLocales.includes(locale) ? locale : defaultLocale;
  }

  function getStoredLocale() {
    const current = localStorage.getItem(storageKey);
    if (current) return normalizeLocale(current);
    const legacy = localStorage.getItem(legacyStorageKey);
    if (legacy) {
      const normalized = normalizeLocale(legacy);
      localStorage.setItem(storageKey, normalized);
      localStorage.removeItem(legacyStorageKey);
      return normalized;
    }
    return defaultLocale;
  }

  let currentLocale = getStoredLocale();
  const missingKeys = new Set();

  function t(key, params = {}) {
    const localeDictionary = dictionaries[currentLocale] || dictionaries[defaultLocale];
    let value = localeDictionary[key] ?? dictionaries[defaultLocale][key];
    if (value === undefined) {
      missingKeys.add(`${currentLocale}:${key}`);
      return key;
    }
    Object.entries(params).forEach(([name, replacement]) => {
      value = value.replaceAll(`{${name}}`, String(replacement));
    });
    return value;
  }

  function setLocale(locale) {
    currentLocale = normalizeLocale(locale);
    localStorage.setItem(storageKey, currentLocale);
    applyTranslations();
    window.dispatchEvent(new CustomEvent("archive:locale-changed", { detail: { locale: currentLocale } }));
  }

  function formatNumber(value) {
    return Number(value ?? 0).toLocaleString(currentLocale);
  }

  function formatMoney(value) {
    return Number(value ?? 0).toLocaleString(currentLocale);
  }

  function formatDateTime(value) {
    return new Date(value).toLocaleString(currentLocale);
  }

  function translateStatus(value) {
    if (!value) return t("status.UNKNOWN");
    return t(`status.${value}`);
  }

  function applyTranslations() {
    document.documentElement.lang = currentLocale;
    document.documentElement.dataset.language = currentLocale;

    document.querySelectorAll("[data-i18n]").forEach((element) => {
      element.textContent = t(element.dataset.i18n);
    });
    document.querySelectorAll("[data-i18n-html]").forEach((element) => {
      element.innerHTML = t(element.dataset.i18nHtml);
    });
    document.querySelectorAll("[data-i18n-aria-label]").forEach((element) => {
      element.setAttribute("aria-label", t(element.dataset.i18nAriaLabel));
    });
    document.querySelectorAll("[data-i18n-title]").forEach((element) => {
      element.setAttribute("title", t(element.dataset.i18nTitle));
    });

    const selector = document.getElementById("languageSelector");
    if (selector) {
      selector.value = currentLocale;
      Array.from(selector.options).forEach((option) => {
        option.textContent = localeNames[option.value] ?? option.textContent;
      });
    }
  }

  window.ArchiveLedgerI18n = {
    supportedLocales,
    localeNames,
    storageKey,
    getLocale: () => currentLocale,
    setLocale,
    t,
    formatNumber,
    formatMoney,
    formatDateTime,
    translateStatus,
    applyTranslations,
    missingKeys: () => Array.from(missingKeys)
  };
})();
