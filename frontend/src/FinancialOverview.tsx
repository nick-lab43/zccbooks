import { useEffect, useMemo, useState } from "react";

type Unit = { id: string; level: string; code: string; name: string };
type Summary = { contributionCount: number; currencyTotals: { currency: string; totalAmount: number }[] };
type Position = { currency: string; income: number; expenditure: number; netPosition: number };
type IncomeExpenditure = { positions: Position[] };

export default function FinancialOverview({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [from, setFrom] = useState(new Date(new Date().getFullYear(), 0, 1).toISOString().slice(0, 10));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [summary, setSummary] = useState<Summary | null>(null);
  const [position, setPosition] = useState<Position[]>([]);
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
    setUnits([]); setScopeUnitId(""); setSummary(null); setPosition([]); setError("");
    if (!organisationId) return;
    request<Unit[]>(`/api/organisations/${organisationId}/financial-reports/units`)
      .then(rows => {
        const available = rows.filter(row => ["COUNTRY", "PROVINCE_STATE", "DISTRICT", "NYIKA", "TABHERA", "CELL"].includes(row.level));
        setUnits(available);
        setScopeUnitId(current => available.some(row => row.id === current) ? current : available[0]?.id ?? "");
      })
      .catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  useEffect(() => {
    if (!organisationId || !scopeUnitId || !from || !to || from > to) return;
    let cancelled = false;
    setBusy(true); setError("");
    const query = new URLSearchParams({ scopeUnitId, from, to }).toString();
    Promise.all([
      request<Summary>(`/api/organisations/${organisationId}/financial-reports/summary?${query}`),
      request<IncomeExpenditure>(`/api/organisations/${organisationId}/financial-reports/income-expenditure?${query}`)
    ]).then(([summaryResult, positionResult]) => {
      if (cancelled) return;
      setSummary(summaryResult);
      setPosition(positionResult.positions ?? []);
    }).catch((e: Error) => {
      if (cancelled) return;
      setSummary(null); setPosition([]); setError(e.message);
    }).finally(() => { if (!cancelled) setBusy(false); });
    return () => { cancelled = true; };
  }, [organisationId, scopeUnitId, from, to, authorizationHeader]);

  const money = (value: number, currency: string) => `${currency} ${Number(value ?? 0).toLocaleString("en-ZA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  return <section className="panel financial-overview">
    <div className="panel-title"><div><span className="panel-index">FIN</span><h2>Financial position</h2></div><span className="panel-tag">POSTED ACTIVITY</span></div>
    <p className="panel-description">A live summary of recorded contributions and the net financial position for the selected unit and period. Figures follow your assigned financial reporting scope.</p>
    <div className="field-pair">
      <label>Geographical scope<select value={scopeUnitId} onChange={event => setScopeUnitId(event.target.value)}><option value="">Select scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
      <div className="field-pair"><label>From<input type="date" value={from} max={to} onChange={event => setFrom(event.target.value)} /></label><label>To<input type="date" value={to} min={from} onChange={event => setTo(event.target.value)} /></label></div>
    </div>
    {error && <div className="message error" role="alert">{error}</div>}
    {busy && <p className="panel-description">Loading financial position…</p>}
    {!busy && summary && <>
      <div className="overview-stat-grid">
        <article className="overview-stat"><span>CONTRIBUTIONS RECORDED</span><strong>{summary.contributionCount.toLocaleString("en-ZA")}</strong></article>
        {summary.currencyTotals.map(total => <article className="overview-stat" key={total.currency}><span>CONTRIBUTIONS · {total.currency}</span><strong>{money(total.totalAmount, total.currency)}</strong></article>)}
      </div>
      <div className="table-wrap"><table><thead><tr><th>CURRENCY</th><th>INCOME</th><th>EXPENDITURE</th><th>NET POSITION</th></tr></thead><tbody>
        {position.map(row => <tr key={row.currency}><td><strong>{row.currency}</strong></td><td>{money(row.income, row.currency)}</td><td>{money(row.expenditure, row.currency)}</td><td><strong>{money(row.netPosition, row.currency)}</strong></td></tr>)}
        {!position.length && <tr><td colSpan={4} className="empty-table">No posted income or expenditure for this period.</td></tr>}
      </tbody></table></div>
    </>}
    {!busy && !error && !summary && <div className="empty-note">{units.length ? "Select a geographical scope to view financial activity." : "No financial reporting units are available to this account."}</div>}
  </section>;
}
