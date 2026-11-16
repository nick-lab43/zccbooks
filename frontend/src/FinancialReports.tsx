import { useEffect, useMemo, useState } from "react";

type Unit = { id: string; level: string; code: string; name: string };
type Summary = { context: { unitName: string; unitLevel: string; from: string; to: string }; contributionCount: number; totalAmount: number; groups: { categoryName: string; streamName: string; contributionCount: number; totalAmount: number }[] };
type Contribution = { receiptNumber: string; contributionDate: string; firstName: string; lastName: string; categoryName: string; streamName: string; paymentMethod: string; amount: number; currency: string };
type Deposit = { id: string; depositDate: string; unitName: string; bankName: string; bankReference: string; expectedAmount: number; status: string; allocatedAmount: number; allocationCount: number };
type Exception = { id: string; exceptionType: string; status: string; summary: string; createdAt: string; unitName: string };

export default function FinancialReports({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [from, setFrom] = useState(new Date(new Date().getFullYear(), 0, 1).toISOString().slice(0, 10));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [type, setType] = useState("CONTRIBUTION_SUMMARY");
  const [summary, setSummary] = useState<Summary | null>(null);
  const [contributions, setContributions] = useState<Contribution[]>([]);
  const [deposits, setDeposits] = useState<Deposit[]>([]);
  const [exceptions, setExceptions] = useState<Exception[]>([]);
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
        const available = rows.filter(row => ["NYIKA", "TABHERA", "CELL"].includes(row.level));
        setUnits(available);
        setScopeUnitId(current => available.some(row => row.id === current) ? current : available[0]?.id ?? "");
      })
      .catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  function params() {
    const query = new URLSearchParams({ scopeUnitId, from, to });
    return query.toString();
  }

  async function loadReport() {
    if (!scopeUnitId || !from || !to || from > to) {
      setError("Select a geographical scope and a valid date range.");
      return;
    }
    setBusy(true); setError("");
    try {
      const query = params();
      if (type === "CONTRIBUTION_SUMMARY") setSummary(await request<Summary>(`/api/organisations/${organisationId}/financial-reports/summary?${query}`));
      if (type === "CONTRIBUTION_REGISTER") setContributions(await request<Contribution[]>(`/api/organisations/${organisationId}/financial-reports/contributions?${query}`));
      if (type === "DEPOSIT_REGISTER") setDeposits(await request<Deposit[]>(`/api/organisations/${organisationId}/financial-reports/deposits?${query}`));
      if (type === "EXCEPTION_REGISTER") setExceptions(await request<Exception[]>(`/api/organisations/${organisationId}/financial-reports/exceptions?${query}`));
    } catch (e) { setError(e instanceof Error ? e.message : "Report could not be loaded."); }
    finally { setBusy(false); }
  }

  async function download() {
    if (!scopeUnitId || from > to) { setError("Select a valid scope and date range."); return; }
    setBusy(true); setError("");
    try {
      const response = await fetch(`${apiBase}/api/organisations/${organisationId}/financial-reports/export?${params()}&type=${type}`, { headers });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Export failed (${response.status}).`);
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `zcc-${type.toLowerCase().replace(/_/g, "-")}-${from}-to-${to}.docx`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (e) { setError(e instanceof Error ? e.message : "Word export failed."); }
    finally { setBusy(false); }
  }

  return <div className="financial-reports">
    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">01</span><h2>Financial report parameters</h2></div><span className="panel-tag">AUTHORISATION SCOPED</span></div>
      <p className="panel-description">Reports include records in the selected geographical unit and its descendants. Access is checked by the API for every request.</p>
      <div className="form-stack">
        <label>Report<select value={type} onChange={e => setType(e.target.value)}>
          <option value="CONTRIBUTION_SUMMARY">Contribution summary</option>
          <option value="CONTRIBUTION_REGISTER">Contributor and receipt register</option>
          <option value="DEPOSIT_REGISTER">Deposit register</option>
          <option value="EXCEPTION_REGISTER">Reconciliation exceptions</option>
        </select></label>
        <label>Geographical scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)}><option value="">Select scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
        <div className="field-pair"><label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label><label>To<input type="date" value={to} onChange={e => setTo(e.target.value)} /></label></div>
        {error && <div className="message error" role="alert">{error}</div>}
        <div className="field-pair"><button className="secondary-button" disabled={busy || !scopeUnitId} onClick={loadReport}>{busy ? "Working…" : "Preview report"}</button><button className="primary-button" disabled={busy || !scopeUnitId} onClick={download}>Download Word report</button></div>
      </div>
    </section>
    {summary && type === "CONTRIBUTION_SUMMARY" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Contribution summary</h2></div><span className="panel-tag">{summary.context.unitName}</span></div><div className="overview-stat-grid"><article className="overview-stat"><span>TRANSACTIONS</span><strong>{summary.contributionCount}</strong></article><article className="overview-stat"><span>TOTAL CONTRIBUTIONS</span><strong>R {Number(summary.totalAmount).toLocaleString("en-ZA", {minimumFractionDigits:2})}</strong></article></div><div className="table-wrap"><table><thead><tr><th>STREAM</th><th>CATEGORY</th><th>COUNT</th><th>TOTAL</th></tr></thead><tbody>{summary.groups.map((row,i)=><tr key={i}><td>{row.streamName}</td><td>{row.categoryName}</td><td>{row.contributionCount}</td><td>R {Number(row.totalAmount).toLocaleString("en-ZA",{minimumFractionDigits:2})}</td></tr>)}{!summary.groups.length && <tr><td colSpan={4} className="empty-table">No contributions were recorded for this period.</td></tr>}</tbody></table></div></section>}
    {contributions.length > 0 && type === "CONTRIBUTION_REGISTER" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Contributor and receipt register</h2></div><span className="panel-tag">{contributions.length} RECORDS</span></div><div className="table-wrap"><table><thead><tr><th>DATE</th><th>RECEIPT</th><th>CONTRIBUTOR</th><th>CATEGORY</th><th>STREAM</th><th>AMOUNT</th></tr></thead><tbody>{contributions.map(row=><tr key={row.receiptNumber}><td>{row.contributionDate}</td><td>{row.receiptNumber}</td><td>{row.firstName} {row.lastName}</td><td>{row.categoryName}</td><td>{row.streamName}</td><td>{row.currency} {Number(row.amount).toFixed(2)}</td></tr>)}</tbody></table></div></section>}
    {deposits.length > 0 && type === "DEPOSIT_REGISTER" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Deposit register</h2></div><span className="panel-tag">{deposits.length} RECORDS</span></div><div className="table-wrap"><table><thead><tr><th>DATE</th><th>UNIT</th><th>BANK</th><th>REFERENCE</th><th>EXPECTED</th><th>ALLOCATED</th><th>STATUS</th></tr></thead><tbody>{deposits.map(row=><tr key={row.id}><td>{row.depositDate}</td><td>{row.unitName}</td><td>{row.bankName}</td><td>{row.bankReference}</td><td>R {Number(row.expectedAmount).toFixed(2)}</td><td>R {Number(row.allocatedAmount).toFixed(2)}</td><td>{row.status}</td></tr>)}</tbody></table></div></section>}
    {exceptions.length > 0 && type === "EXCEPTION_REGISTER" && <section className="panel"><div className="panel-title"><div><span className="panel-index">02</span><h2>Reconciliation exceptions</h2></div><span className="panel-tag">{exceptions.length} RECORDS</span></div><div className="table-wrap"><table><thead><tr><th>DATE</th><th>UNIT</th><th>TYPE</th><th>STATUS</th><th>SUMMARY</th></tr></thead><tbody>{exceptions.map(row=><tr key={row.id}><td>{new Date(row.createdAt).toLocaleDateString()}</td><td>{row.unitName}</td><td>{row.exceptionType}</td><td>{row.status}</td><td>{row.summary}</td></tr>)}</tbody></table></div></section>}
  </div>;
}
