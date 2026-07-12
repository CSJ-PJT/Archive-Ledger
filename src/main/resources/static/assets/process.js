const sourceLogistics = "Archive-Logitics";
const sourceNexus = "Archive-Nexus";

const byId = (id) => document.getElementById(id);
const i18n = window.ArchiveLedgerI18n;

function initLanguageSelector() {
  const selector = byId("languageSelector");
  if (!selector || !i18n) return;

  selector.innerHTML = i18n.supportedLocales
    .map((locale) => `<option value="${locale}">${i18n.localeNames[locale]}</option>`)
    .join("");
  selector.value = i18n.getLocale();
  selector.addEventListener("change", () => i18n.setLocale(selector.value));
  i18n.applyTranslations();
}

function formatNumber(value) {
  return i18n ? i18n.formatNumber(value) : Number(value ?? 0).toLocaleString("ko-KR");
}

function formatMoney(value) {
  return i18n ? i18n.formatMoney(value) : Number(value ?? 0).toLocaleString("ko-KR");
}

function translate(key, params) {
  return i18n ? i18n.t(key, params) : key;
}

function translateStatus(value) {
  return i18n ? i18n.translateStatus(value) : (value ?? "UNKNOWN");
}

function formatDateTime(value) {
  return i18n ? i18n.formatDateTime(value) : new Date(value).toLocaleString("ko-KR");
}

function formatPercent(value) {
  const ratio = Number(value ?? 0);
  return `${(ratio * 100).toLocaleString(i18n?.getLocale?.() ?? "ko-KR", { maximumFractionDigits: 2 })}%`;
}

async function getJson(path) {
  const response = await fetch(path, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`${path} -> ${response.status}`);
  }
  return response.json();
}

function setText(id, value) {
  const element = byId(id);
  if (element) element.textContent = value;
}

function setStatus(id, value) {
  const element = byId(id);
  if (!element) return;
  const rawValue = value ?? "UNKNOWN";
  element.textContent = translateStatus(rawValue);
  element.title = rawValue;
  element.classList.remove("balanced", "warning", "danger");
  if (rawValue === "HEALTHY" || rawValue === "OK" || rawValue === "UP" || rawValue === "BALANCED") {
    element.classList.add("balanced");
  } else if (rawValue === "WARNING" || rawValue === "DEGRADED" || rawValue === "CHECK") {
    element.classList.add("warning");
  } else {
    element.classList.add("danger");
  }
}

function setSignedMoney(id, value) {
  const element = byId(id);
  if (!element) return;
  const amount = Number(value ?? 0);
  element.textContent = formatMoney(amount);
  element.classList.toggle("balanced", amount > 0);
  element.classList.toggle("danger", amount < 0);
}

function renderRuntimeOverview(runtime, balance, capacity) {
  const available = balance?.available;
  setText("runtimePipeline", runtime?.pipelineStatus ?? "-");
  setText("runtimeLastWork", runtime?.lastWorkAt ? formatDateTime(runtime.lastWorkAt) : translate("runtime.noData"));
  setText("runtimeScope", balance?.calculationScope ?? "-");
  setText("runtimeCalculatedAt", balance?.calculatedAt ? formatDateTime(balance.calculatedAt) : translate("runtime.noData"));
  setSignedMoney("operatingProfit", balance?.operatingProfit);
  setText("cashBalance", formatMoney(balance?.cashBalance));
  setText("operatingMargin", `${translate("runtime.margin")} ${formatPercent(balance?.operatingMargin)}`);
  setText("capacityUtilization", formatPercent(balance?.capacityUtilization ?? capacity?.capacityUtilizationRate));
  setText("capacityAvailable", available === false ? translate("runtime.noData") : translate("runtime.available"));
  setText("settlementDelayRate", formatPercent(balance?.settlementDelayRate));
  setText("bottleneckRole", balance?.bottleneckRole ?? capacity?.bottleneckRole ?? "NONE");
  setText("runtimeBacklog", translate("runtime.backlogDetail", {
    approval: formatNumber(balance?.approvalBacklog),
    settlement: formatNumber(balance?.settlementBacklog),
    reconciliation: formatNumber(balance?.reconciliationBacklog),
    callback: formatNumber(balance?.callbackBacklog)
  }));
}

function renderBalance(id, summary) {
  const debit = Number(summary?.totalDebit ?? 0);
  const credit = Number(summary?.totalCredit ?? 0);
  const balanced = debit === credit;
  const element = byId(id);
  if (!element) return balanced;
  const entryCount = summary?.entryCount ?? 0;
  element.textContent = `${translate("ledger.debitShort")} ${formatMoney(debit)} / ${translate("ledger.creditShort")} ${formatMoney(credit)} (${formatNumber(entryCount)} ${translate("ledger.entries")})`;
  element.classList.toggle("balanced", balanced);
  element.classList.toggle("danger", !balanced);
  return balanced;
}

function renderTransactions(rows) {
  const target = byId("transactionRows");
  if (!target) return;
  const selected = Array.isArray(rows) ? rows.slice(0, 10) : [];
  if (selected.length === 0) {
    target.innerHTML = `<tr><td colspan="6">${translate("transactions.empty")}</td></tr>`;
    return;
  }
  target.innerHTML = selected.map((row) => {
    const status = row.status ?? "-";
    return `
      <tr>
        <td><code>${row.transactionId ?? "-"}</code></td>
        <td>${row.transactionType ?? "-"}</td>
        <td title="${status}">${translateStatus(status)}</td>
        <td>${formatMoney(row.amount)} ${row.currency ?? ""}</td>
        <td>${row.approvalRequired ? translate("approval.required") : translate("approval.notRequired")}</td>
        <td><time datetime="${row.createdAt ?? ""}">${row.createdAt ? formatDateTime(row.createdAt) : "-"}</time></td>
      </tr>
    `;
  }).join("");
}

async function refreshDashboard() {
  const [
    health,
    operations,
    reconciliation,
    logisticsLedger,
    nexusLedger,
    transactions,
    runtime,
    settlementAgency,
    capacity
  ] = await Promise.all([
    getJson("/actuator/health"),
    getJson("/api/operations/summary"),
    getJson("/api/reconciliation/summary"),
    getJson(`/api/ledger/summary?source=${encodeURIComponent(sourceLogistics)}`),
    getJson(`/api/ledger/summary?source=${encodeURIComponent(sourceNexus)}`),
    getJson("/api/transactions"),
    getJson("/api/runtime/status"),
    getJson("/api/settlement-agency/summary"),
    getJson("/api/capacity/summary")
  ]);

  setStatus("serviceStatus", operations.status ?? health.status ?? "UNKNOWN");
  setStatus("reconciliationStatus", reconciliation.status ?? operations.lastReconciliationStatus ?? "UNKNOWN");
  setText("receivedEvents", formatNumber(operations.receivedEvents));
  setText("transactions", formatNumber(operations.transactions));
  setText("mismatch", formatNumber(reconciliation.mismatch));
  setText("nexusEvents", formatNumber(operations.eventsReceivedFromNexus));
  setText("logisticsEvents", formatNumber(operations.eventsReceivedFromLogitics));
  setText("duplicates", formatNumber(operations.duplicates));
  setText("createdTransactions", formatNumber(reconciliation.createdTransactions ?? operations.transactions));
  setText("approvalRequired", formatNumber(operations.approvalRequired));
  setText("settled", formatNumber(operations.settled));
  setText("sourceNexus", formatNumber(operations.eventsReceivedFromNexus));
  setText("sourceLogistics", formatNumber(operations.eventsReceivedFromLogitics));
  setText("failedEvents", formatNumber(operations.failed));
  setText("settlementReady", formatNumber(reconciliation.settlementReady));
  setText("reconciliationDetail", `${formatNumber(reconciliation.receivedEvents)} - ${formatNumber(reconciliation.duplicates)} - ${formatNumber(reconciliation.createdTransactions)} - ${formatNumber(reconciliation.failed)}`);

  const logisticsBalanced = renderBalance("logisticsDebitCredit", logisticsLedger);
  const nexusBalanced = renderBalance("nexusDebitCredit", nexusLedger);
  setStatus("ledgerBalance", logisticsBalanced && nexusBalanced ? "BALANCED" : "CHECK");

  renderRuntimeOverview(runtime, settlementAgency.balance ?? operations.balance, capacity);
  renderTransactions(transactions);
  setText("lastUpdated", translate("common.updatedAt", { time: formatDateTime(new Date()) }));
}

async function guardedRefresh() {
  const button = byId("refreshButton");
  if (button) button.disabled = true;
  try {
    await refreshDashboard();
  } catch (error) {
    setStatus("serviceStatus", "UNAVAILABLE");
    setStatus("reconciliationStatus", "UNKNOWN");
    const lastUpdated = byId("lastUpdated");
    if (lastUpdated) {
      lastUpdated.textContent = translate("common.unavailable");
      lastUpdated.title = error.message;
    }
  } finally {
    if (button) button.disabled = false;
  }
}

byId("refreshButton")?.addEventListener("click", guardedRefresh);
window.addEventListener("archive:locale-changed", guardedRefresh);
initLanguageSelector();
guardedRefresh();
setInterval(guardedRefresh, 30000);
