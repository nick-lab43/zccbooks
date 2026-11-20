import { useEffect, useMemo, useState } from "react";

type Unit = { id: string; level: string; code: string; name: string };
type Summary = { context: { unitName: string; unitLevel: string; from: string; to: string }; contributionCount: number; currencyTotals: { currency: string; totalAmount: number }[]; groups: { categoryName: string; streamName: string; currency: string; contributionCount: number; totalAmount: number }[] };
type Breakdown = { groupName: string; contributionCount: number; totalAmount: number; currency: string };
type Contribution = { receiptNumber: string; contributionDate: string; contributorName: string; contributorType: "HOUSEHOLD" | "INDIVIDUAL"; classificationName: string | null; eliteNumber: string | null; categoryName: string; streamName: string; paymentMethod: string; amount: number; currency: string };
type Deposit = { id: string; depositDate: string; unitName: string; bankName: string; bankReference: string; expectedAmount: number; status: string; allocatedAmount: number; allocationCount: number; bankedAmount: number; outstandingAmount: number };
type Exception = { id: string; exceptionType: string; status: string; summary: string; createdAt: string; unitName: string };
type Expense = { id: string; expenseDate: string; unitName: string; categoryName: string; payee: string; description: string; reference: string | null; amount: number; currency: string; status: string; paymentReference: string | null; reviewNote: string | null };
type Position = { currency: string; income: number; expenditure: number; netPosition: number };
type IncomeExpenditure = { context: { unitName: string; from: string; to: string }; positions: Position[] };
type BudgetPerformance = { id: string; periodStart: string; periodEnd: string; unitName: string; categoryCode: string; categoryName: string; budgetAmount: number; actualAmount: number; remainingAmount: number; utilisationPercentage: number; currency: string };

function toLocalDateInputValue(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function dateRangeForPreset(preset: string, now = new Date()): { from: string; to: string } {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (preset === "MONTH_TO_DATE") return { from: toLocalDateInputValue(new Date(today.getFullYear(), today.getMonth(), 1)), to: toLocalDateInputValue(today) };
  if (preset === "PREVIOUS_MONTH") return { from: toLocalDateInputValue(new Date(today.getFullYear(), today.getMonth() - 1, 1)), to: toLocalDateInputValue(new Date(today.getFullYear(), today.getMonth(), 0)) };
  if (preset === "QUARTER_TO_DATE") {
    const quarterStartMonth = Math.floor(today.getMonth() / 3) * 3;
    return { from: toLocalDateInputValue(new Date(today.getFullYear(), quarterStartMonth, 1)), to: toLocalDateInputValue(today) };
  }
  if (preset === "YEAR_TO_DATE") return { from: toLocalDateInputValue(new Date(today.getFullYear(), 0, 1)), to: toLocalDateInputValue(today) };
  return { from: toLocalDateInputValue(today), to: toLocalDateInputValue(today) };
}

function formatReportDate(value: string): string {
  if (!value) return "—";
  return new Date(`${value}T00:00:00`).toLocaleDateString("en-ZA", { day: "numeric", month: "short", year: "numeric" });
}

function formatReportAmount(currency: string, amount: number): string {
  return `${currency} ${Number(amount).toLocaleString("en-ZA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default function FinancialReports({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const initialPeriod = dateRangeForPreset("YEAR_TO_DATE");
  const [from, setFrom] = useState(initialPeriod.from);
  const [to, setTo] = useState(initialPeriod.to);
  const [periodPreset, setPeriodPreset] = useState("YEAR_TO_DATE");
  const [type, setType] = useState("CONTRIBUTION_SUMMARY");
  const [summary, setSummary] = useState<Summary | null>(null);
  const [contributions, setContributions] = useState<Contribution[]>([]);
  const [breakdown, setBreakdown] = useState<Breakdown[]>([]);
  const [groupBy, setGroupBy] = useState("CATEGORY");
  const [deposits, setDeposits] = useState<Deposit[]>([]);
  const [exceptions, setExceptions] = useState<Exception[]>([]);
  const [expenses, setExpenses] = useState<Expense[]>([]);
  const [incomeExpenditure, setIncomeExpenditure] = useState<IncomeExpenditure | null>(null);
  const [budgetPerformance, setBudgetPerformance] = useState<BudgetPerformance[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, { headers });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.json() as Promise<T>;
  }

  useEffect(() => {
    if (!organisationId) return;
    request<Unit[]>(`/api/organisations/${organisationId}/financial-reports/units`)
      .then(rows => {
        const available = rows.filter(row => ["COUNTRY", "PROVINCE_STATE", "DISTRICT", "NYIKA", "TABHERA", "CELL"].includes(row.level));
        setUnits(available);
        setScopeUnitId(current => available.some(row => row.id === current) ? current : available[0]?.id ?? "");
      })
      .catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  function applyPeriodPreset(preset: string) {
    setPeriodPreset(preset);
    if (preset === "CUSTOM") return;
    const range = dateRangeForPreset(preset);
    setFrom(range.from);
    setTo(range.to);
  }

  function params() {
    const query = new URLSearchParams({ scopeUnitId, from, to, groupBy });
    return query.toString();
  }

  async function loadReport() {
    if (!scopeUnitId || !from || !to || from > to) {
      setError("Select a geographical scope and a valid date range.");
      return;
    }
    setBusy(true); setError("");
    setSummary(null); setContributions([]); setBreakdown([]); setDeposits([]); setExceptions([]); setExpenses([]); setIncomeExpenditure(null); setBudgetPerformance([]);
    try {
      const query = params();
      if (type === "CONTRIBUTION_SUMMARY") setSummary(await request<Summary>(`/api/organisations/${organisationId}/financial-reports/summary?${query}`));
      if (type === "CONTRIBUTION_BREAKDOWN") setBreakdown(await request<Breakdown[]>(`/api/organisations/${organisationId}/financial-reports/contribution-breakdown?${query}`));
      if (type === "CONTRIBUTION_REGISTER") setContributions(await request<Contribution[]>(`/api/organisations/${organisationId}/financial-reports/contributions?${query}`));
      if (type === "DEPOSIT_REGISTER") setDeposits(await request<Deposit[]>(`/api/organisations/${organisationId}/financial-reports/deposits?${query}`));
      if (type === "EXCEPTION_REGISTER") setExceptions(await request<Exception[]>(`/api/organisations/${organisationId}/financial-reports/exceptions?${query}`));
      if (type === "EXPENDITURE_REGISTER") setExpenses(await request<Expense[]>(`/api/organisations/${organisationId}/financial-reports/expenses?${query}`));
      if (type === "INCOME_EXPENDITURE") setIncomeExpenditure(await request<IncomeExpenditure>(`/api/organisations/${organisationId}/financial-reports/income-expenditure?${query}`));
      if (type === "BUDGET_PERFORMANCE") setBudgetPerformance(await request<BudgetPerformance[]>(`/api/organisations/${organisationId}/financial-reports/budget-performance?${query}`));
    } catch (e) { setError(e instanceof Error ? e.message : "Report could not be loaded."); }
    finally { setBusy(false); }
  }

  async function download(format: "docx" | "pdf") {
    if (!scopeUnitId || !from || !to || from > to) { setError("Select a valid scope and date range."); return; }
    setBusy(true); setError("");
    try {
      const endpoint = format === "pdf" ? "export/pdf" : "export";
      const response = await fetch(`${apiBase}/api/organisations/${organisationId}/financial-reports/${endpoint}?${params()}&type=${type}`, { headers });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Export failed (${response.status}).`);
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `zcc-${type.toLowerCase().replace(/_/g, "-")}-${from}-to-${to}.${format}`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (e) { setError(e instanceof Error ? e.message : `${format.toUpperCase()} export failed.`); }
    finally { setBusy(false); }
  }

  return <div className="financial-reports contribution-reporting-workspace">
    <header className="reporting-hero">
      <div>
        <span className="reporting-eyebrow">ZCC BOOKS / FINANCIAL INTELLIGENCE</span>
        <h1>Financial reporting</h1>
        <p>Understand contributions across reporting periods, categories, Cells and households.</p>
      </div>
      <div className="reporting-hero-meta"><span>REPORTING PERIOD</span><strong>{formatReportDate(from)} – {formatReportDate(to)}</strong><small>Access follows your assigned geographical authority</small></div>
    </header>

    <nav className="contribution-report-shortcuts" aria-label="Contribution report views">
      <button type="button" className={type === "CONTRIBUTION_SUMMARY" ? "active" : ""} onClick={() => setType("CONTRIBUTION_SUMMARY")}><span>01</span><strong>Overview</strong><small>Period totals and categories</small></button>
      <button type="button" className={type === "CONTRIBUTION_BREAKDOWN" && groupBy === "CATEGORY" ? "active" : ""} onClick={() => { setType("CONTRIBUTION_BREAKDOWN"); setGroupBy("CATEGORY"); }}><span>02</span><strong>By category</strong><small>Contribution streams</small></button>
      <button type="button" className={type === "CONTRIBUTION_BREAKDOWN" && groupBy === "CELL" ? "active" : ""} onClick={() => { setType("CONTRIBUTION_BREAKDOWN"); setGroupBy("CELL"); }}><span>03</span><strong>By Cell</strong><small>Geographical performance</small></button>
      <button type="button" className={type === "CONTRIBUTION_BREAKDOWN" && groupBy === "HOUSEHOLD" ? "active" : ""} onClick={() => { setType("CONTRIBUTION_BREAKDOWN"); setGroupBy("HOUSEHOLD"); }}><span>04</span><strong>By household</strong><small>Household contributions</small></button>
    </nav>

    <section className="panel reporting-parameters">
      <div className="panel-title"><div><span className="panel-index">01</span><h2>Build your report</h2></div><span className="panel-tag">AUTHORISATION SCOPED</span></div>
      <p className="panel-description">Choose a reporting view, geographical scope and period. Results include records in the selected unit and its descendants; the API checks access on every request.</p>
      <div className="form-stack">
        <label>Report<select value={type} onChange={e => setType(e.target.value)}>
          <option value="CONTRIBUTION_SUMMARY">Contribution summary</option>
          <option value="CONTRIBUTION_REGISTER">Contributor and receipt register</option>
          <option value="CONTRIBUTION_BREAKDOWN">Contribution breakdown</option>
          <option value="DEPOSIT_REGISTER">Deposit register</option>
          <option value="EXPENDITURE_REGISTER">Expenditure register</option>
          <option value="INCOME_EXPENDITURE">Income and expenditure summary</option>
          <option value="BUDGET_PERFORMANCE">Expenditure budget performance</option>
          <option value="EXCEPTION_REGISTER">Reconciliation exceptions</option>
        </select></label>
        {type === "CONTRIBUTION_BREAKDOWN" && <label>Group contributions by<select value={groupBy} onChange={e => setGroupBy(e.target.value)}>
          <option value="CATEGORY">Contribution category</option><option value="HOUSEHOLD">Household</option><option value="MEMBER">Member</option><option value="CLASSIFICATION">Classification</option><option value="CELL">Cell</option>
        </select></label>}
        <label>Geographical scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)}><option value="">Select scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
        <div className="reporting-period-picker">
          <span>REPORTING PERIOD</span>
          <div className="reporting-period-options">
            <button type="button" className={periodPreset === "MONTH_TO_DATE" ? "active" : ""} onClick={() => applyPeriodPreset("MONTH_TO_DATE")}>This month</button>
            <button type="button" className={periodPreset === "PREVIOUS_MONTH" ? "active" : ""} onClick={() => applyPeriodPreset("PREVIOUS_MONTH")}>Last month</button>
            <button type="button" className={periodPreset === "QUARTER_TO_DATE" ? "active" : ""} onClick={() => applyPeriodPreset("QUARTER_TO_DATE")}>This quarter</button>
            <button type="button" className={periodPreset === "YEAR_TO_DATE" ? "active" : ""} onClick={() => applyPeriodPreset("YEAR_TO_DATE")}>Year to date</button>
            <button type="button" className={periodPreset === "CUSTOM" ? "active" : ""} onClick={() => setPeriodPreset("CUSTOM")}>Custom</button>
          </div>
        </div>
        <div className="field-pair reporting-date-fields"><label>From<input type="date" value={from} max={to} onChange={e => { setFrom(e.target.value); setPeriodPreset("CUSTOM"); }} /></label><label>To<input type="date" value={to} min={from} onChange={e => { setTo(e.target.value); setPeriodPreset("CUSTOM"); }} /></label></div>
        {error && <div className="message error" role="alert">{error}</div>}
        <div className="field-pair"><button className="secondary-button" disabled={busy || !scopeUnitId} onClick={loadReport}>{busy ? "Working…" : "Preview report"}</button><button className="primary-button" disabled={busy || !scopeUnitId} onClick={() => download("docx")}>Download Word</button><button className="secondary-button" disabled={busy || !scopeUnitId} onClick={() => download("pdf")}>Download PDF</button></div>
      </div>
    </section>
    {summary && type === "CONTRIBUTION_SUMMARY" && <section className="panel reporting-results">
      <div className="reporting-results-heading"><div><span className="reporting-eyebrow">PERIOD PERFORMANCE</span><h2>Contribution summary</h2><p>{summary.context.unitName} · {formatReportDate(summary.context.from)} to {formatReportDate(summary.context.to)}</p></div><span className="reporting-result-count">{summary.contributionCount.toLocaleString("en-ZA")} TRANSACTIONS</span></div>
      <div className="reporting-total-grid">
        {summary.currencyTotals.map(total => <article className="reporting-total-card" key={total.currency}><span>TOTAL CONTRIBUTIONS · {total.currency}</span><strong>{formatReportAmount(total.currency, total.totalAmount)}</strong><small>Recorded in selected period</small></article>)}
        <article className="reporting-total-card reporting-total-count"><span>CONTRIBUTION RECORDS</span><strong>{summary.contributionCount.toLocaleString("en-ZA")}</strong><small>Across all active categories</small></article>
      </div>
      <div className="reporting-table-heading"><div><h3>Category performance</h3><p>Contribution totals by category and stream, separated by currency</p></div><span>{summary.groups.length} LINES</span></div>
      <div className="table-wrap"><table><thead><tr><th>STREAM</th><th>CATEGORY</th><th>COUNT</th><th>CURRENCY</th><th>TOTAL</th></tr></thead><tbody>{summary.groups.map((row,i)=><tr key={i}><td>{row.streamName}</td><td><strong>{row.categoryName}</strong></td><td>{row.contributionCount}</td><td>{row.currency}</td><td><strong>{formatReportAmount(row.currency,row.totalAmount)}</strong></td></tr>)}{!summary.groups.length && <tr><td colSpan={5} className="empty-table">No contributions were recorded for this period.</td></tr>}</tbody></table></div>
    </section>}
    {type === "CONTRIBUTION_BREAKDOWN" && <section className="panel reporting-results">
      <div className="reporting-results-heading"><div><span className="reporting-eyebrow">CONTRIBUTION ANALYSIS</span><h2>{groupBy === "CELL" ? "Contribution by Cell" : groupBy === "HOUSEHOLD" ? "Contribution by household" : groupBy === "CATEGORY" ? "Contribution by category" : "Contribution breakdown"}</h2><p>{units.find(unit => unit.id === scopeUnitId)?.name ?? "Selected scope"} · {formatReportDate(from)} to {formatReportDate(to)}</p></div><span className="reporting-result-count">{breakdown.length} GROUPS</span></div>
      <p className="panel-description">Totals are grouped by the selected dimension and kept separate by currency. Reversed contributions are excluded.</p>
      <div className="table-wrap"><table><thead><tr><th>{groupBy === "CELL" ? "CELL" : groupBy === "HOUSEHOLD" ? "HOUSEHOLD" : "CATEGORY / GROUP"}</th><th>TRANSACTIONS</th><th>CURRENCY</th><th>TOTAL CONTRIBUTIONS</th></tr></thead><tbody>{breakdown.map((row,i)=>{const sameCurrency = breakdown.filter(item => item.currency === row.currency); const maxAmount = Math.max(1,...sameCurrency.map(item => Number(item.totalAmount))); const width = Math.max(3, Number(row.totalAmount) / maxAmount * 100); return <tr key={i}><td><strong>{row.groupName}</strong><div className="reporting-inline-bar"><span style={{width:`${width}%`}} /></div></td><td>{row.contributionCount.toLocaleString("en-ZA")}</td><td>{row.currency}</td><td><strong>{formatReportAmount(row.currency,row.totalAmount)}</strong></td></tr>;})}{!breakdown.length && <tr><td colSpan={4} className="empty-table">No contributions were recorded for this period.</td></tr>}</tbody></table></div>
    </section>}
    {type === "CONTRIBUTION_REGISTER" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Contributor and receipt register</h2></div><span className="panel-tag">{contributions.length} RECORDS</span></div><div className="table-wrap"><table><thead><tr><th>DATE</th><th>RECEIPT</th><th>CONTRIBUTOR</th><th>TYPE</th><th>CLASSIFICATION</th><th>ELITE NUMBER</th><th>CATEGORY</th><th>STREAM</th><th>AMOUNT</th></tr></thead><tbody>{contributions.map(row=><tr key={row.receiptNumber}><td>{row.contributionDate}</td><td>{row.receiptNumber}</td><td>{row.contributorName}</td><td>{row.contributorType}</td><td>{row.classificationName ?? "—"}</td><td>{row.eliteNumber ?? "—"}</td><td>{row.categoryName}</td><td>{row.streamName}</td><td>{row.currency} {Number(row.amount).toFixed(2)}</td></tr>)}{!contributions.length && <tr><td colSpan={9} className="empty-table">No contributions were recorded for this period.</td></tr>}</tbody></table></div></section>}
    {type === "DEPOSIT_REGISTER" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Deposit register</h2></div><span className="panel-tag">{deposits.length} RECORDS</span></div><div className="table-wrap"><table><thead><tr><th>DATE</th><th>UNIT</th><th>BANK</th><th>REFERENCE</th><th>EXPECTED</th><th>ALLOCATED</th><th>BANKED</th><th>OUTSTANDING</th><th>STATUS</th></tr></thead><tbody>{deposits.map(row=><tr key={row.id}><td>{row.depositDate}</td><td>{row.unitName}</td><td>{row.bankName}</td><td>{row.bankReference}</td><td>R {Number(row.expectedAmount).toFixed(2)}</td><td>R {Number(row.allocatedAmount).toFixed(2)}</td><td>R {Number(row.bankedAmount).toFixed(2)}</td><td>R {Number(row.outstandingAmount).toFixed(2)}</td><td>{row.status}</td></tr>)}{!deposits.length && <tr><td colSpan={9} className="empty-table">No deposits were recorded for this period.</td></tr>}</tbody></table></div></section>}
    {type === "EXPENDITURE_REGISTER" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Expenditure register</h2></div><span className="panel-tag">{expenses.length} RECORDS</span></div><div className="table-wrap"><table><thead><tr><th>DATE</th><th>UNIT</th><th>CATEGORY</th><th>PAYEE / DETAILS</th><th>AMOUNT</th><th>STATUS</th></tr></thead><tbody>{expenses.map(row=><tr key={row.id}><td>{row.expenseDate}</td><td>{row.unitName}</td><td>{row.categoryName}</td><td><strong>{row.payee}</strong><small className="table-subtext">{row.description}</small></td><td>{row.currency} {Number(row.amount).toFixed(2)}</td><td>{row.status}</td></tr>)}{!expenses.length && <tr><td colSpan={6} className="empty-table">No expenditure records were recorded for this period.</td></tr>}</tbody></table></div></section>}
    {type === "INCOME_EXPENDITURE" && incomeExpenditure && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Income and expenditure</h2></div><span className="panel-tag">{incomeExpenditure.context.unitName}</span></div><p className="panel-description">Income is based on captured contributions. Expenditure includes approved and paid records only. Draft, submitted and rejected expenses are excluded. Totals remain separate by currency.</p><div className="table-wrap"><table><thead><tr><th>CURRENCY</th><th>INCOME</th><th>APPROVED EXPENDITURE</th><th>NET POSITION</th></tr></thead><tbody>{incomeExpenditure.positions.map(row=><tr key={row.currency}><td><strong>{row.currency}</strong></td><td>{row.currency} {Number(row.income).toFixed(2)}</td><td>{row.currency} {Number(row.expenditure).toFixed(2)}</td><td><strong>{row.currency} {Number(row.netPosition).toFixed(2)}</strong></td></tr>)}{!incomeExpenditure.positions.length && <tr><td colSpan={4} className="empty-table">No income or approved expenditure was recorded for this period.</td></tr>}</tbody></table></div></section>}
    {type === "BUDGET_PERFORMANCE" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Expenditure budget performance</h2></div><span className="panel-tag">{budgetPerformance.length} BUDGET LINES</span></div><p className="panel-description">Each allocation is shown only when its complete budget period falls within the selected reporting dates. Actuals include approved and paid expenditure in the budget unit and descendants. Draft, submitted and rejected expenditure is excluded. Amounts are kept separate by currency.</p><div className="table-wrap"><table><thead><tr><th>PERIOD</th><th>UNIT</th><th>CATEGORY</th><th>BUDGET</th><th>ACTUAL</th><th>REMAINING</th><th>UTILISATION</th></tr></thead><tbody>{budgetPerformance.map(row=><tr key={row.id}><td>{row.periodStart} — {row.periodEnd}</td><td>{row.unitName}</td><td><strong>{row.categoryName}</strong><small className="table-subtext">{row.categoryCode}</small></td><td>{row.currency} {Number(row.budgetAmount).toFixed(2)}</td><td>{row.currency} {Number(row.actualAmount).toFixed(2)}</td><td><strong>{row.currency} {Number(row.remainingAmount).toFixed(2)}</strong><small className="table-subtext">{row.remainingAmount < 0 ? "OVER BUDGET" : "WITHIN BUDGET"}</small></td><td>{Number(row.utilisationPercentage).toFixed(2)}%</td></tr>)}{!budgetPerformance.length && <tr><td colSpan={7} className="empty-table">No complete budget periods were found within the selected dates and geographical scope.</td></tr>}</tbody></table></div></section>}
    {type === "EXCEPTION_REGISTER" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Reconciliation exceptions</h2></div><span className="panel-tag">{exceptions.length} RECORDS</span></div><div className="table-wrap"><table><thead><tr><th>DATE</th><th>UNIT</th><th>TYPE</th><th>STATUS</th><th>SUMMARY</th></tr></thead><tbody>{exceptions.map(row=><tr key={row.id}><td>{new Date(row.createdAt).toLocaleDateString()}</td><td>{row.unitName}</td><td>{row.exceptionType}</td><td>{row.status}</td><td>{row.summary}</td></tr>)}{!exceptions.length && <tr><td colSpan={5} className="empty-table">No reconciliation exceptions were recorded for this period.</td></tr>}</tbody></table></div></section>}
  </div>;
}
