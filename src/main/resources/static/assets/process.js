const sourceLogistics = "Archive-Logitics";
const sourceNexus = "Archive-Nexus";

const byId = (id) => document.getElementById(id);
const formatNumber = (value) => Number(value ?? 0).toLocaleString("ko-KR");
const formatMoney = (value) => Number(value ?? 0).toLocaleString("ko-KR");

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
  element.textContent = value;
  element.classList.remove("balanced", "warning", "danger");
  if (value === "HEALTHY" || value === "OK" || value === "UP") element.classList.add("balanced");
  else if (value === "WARNING" || value === "DEGRADED") element.classList.add("warning");
  else element.classList.add("danger");
}

function renderBalance(id, summary) {
  const debit = Number(summary?.totalDebit ?? 0);
  const credit = Number(summary?.totalCredit ?? 0);
  const balanced = debit === credit;
  const element = byId(id);
  if (!element) return balanced;
  element.textContent = `D ${formatMoney(debit)} / C ${formatMoney(credit)} (${summary?.entryCount ?? 0} entries)`;
  element.classList.toggle("balanced", balanced);
  element.classList.toggle("danger", !balanced);
  return balanced;
}

function renderTransactions(rows) {
  const target = byId("transactionRows");
  if (!target) return;
  const selected = Array.isArray(rows) ? rows.slice(0, 10) : [];
  if (selected.length === 0) {
    target.innerHTML = '<tr><td colspan="5">거래가 없습니다.</td></tr>';
    return;
  }
  target.innerHTML = selected.map((row) => `
    <tr>
      <td><code>${row.transactionId ?? "-"}</code></td>
      <td>${row.transactionType ?? "-"}</td>
      <td>${row.status ?? "-"}</td>
      <td>${formatMoney(row.amount)} ${row.currency ?? ""}</td>
      <td>${row.approvalRequired ? "required" : "not required"}</td>
    </tr>
  `).join("");
}

async function refreshDashboard() {
  const [
    health,
    operations,
    reconciliation,
    logisticsLedger,
    nexusLedger,
    transactions
  ] = await Promise.all([
    getJson("/actuator/health"),
    getJson("/api/operations/summary"),
    getJson("/api/reconciliation/summary"),
    getJson(`/api/ledger/summary?source=${encodeURIComponent(sourceLogistics)}`),
    getJson(`/api/ledger/summary?source=${encodeURIComponent(sourceNexus)}`),
    getJson("/api/transactions")
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
  setText("ledgerBalance", logisticsBalanced && nexusBalanced ? "BALANCED" : "CHECK");
  byId("ledgerBalance")?.classList.toggle("balanced", logisticsBalanced && nexusBalanced);
  byId("ledgerBalance")?.classList.toggle("danger", !(logisticsBalanced && nexusBalanced));

  renderTransactions(transactions);
  setText("lastUpdated", `Updated ${new Date().toLocaleString("ko-KR")}`);
}

async function guardedRefresh() {
  const button = byId("refreshButton");
  if (button) button.disabled = true;
  try {
    await refreshDashboard();
  } catch (error) {
    setStatus("serviceStatus", "UNAVAILABLE");
    setStatus("reconciliationStatus", "UNKNOWN");
    setText("lastUpdated", error.message);
  } finally {
    if (button) button.disabled = false;
  }
}

byId("refreshButton")?.addEventListener("click", guardedRefresh);
guardedRefresh();
setInterval(guardedRefresh, 30000);
