import { useEffect, useMemo, useState } from "react";

type Unit = { id: string; level: string; code: string; name: string };
type Account = { id: string; code: string; name: string; accountType: string; currency: string; active: boolean };
type TrialBalanceLine = { accountId: string; accountCode: string; accountName: string; accountType: string; currency: string; totalDebits: number; totalCredits: number; balanceDebit: number; balanceCredit: number };
type TrialBalance = { scopeUnitId: string; asOf: string; lines: TrialBalanceLine[] };
type StatementLine = { journalId: string; journalNumber: string; journalDate: string; journalDescription: string; unitName: string; debitAmount: number; creditAmount: number; lineDescription: string | null };
type Statement = { account: { id: string; accountCode: string; accountName: string; accountType: string; currency: string }; from: string; to: string; openingBalance: { debit: number; credit: number }; periodDebits: number; periodCredits: number; closingBalance: { debit: number; credit: number }; entries: StatementLine[] };

export default function FinancialLedgerReports({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [view, setView] = useState<"TRIAL_BALANCE" | "ACCOUNT_STATEMENT">("TRIAL_BALANCE");
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [from, setFrom] = useState(new Date(new Date().getFullYear(), 0, 1).toISOString().slice(0, 10));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [accountId, setAccountId] = useState("");
  const [trialBalance, setTrialBalance] = useState<TrialBalance | null>(null);
  const [statement, setStatement] = useState<Statement | null>(null);
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
    Promise.all([
      request<Unit[]>(`/api/organisations/${organisationId}/financial-ledger/units`),
      request<Account[]>(`/api/organisations/${organisationId}/finance-accounts`)
    ]).then(([unitRows, accountRows]) => {
      setUnits(unitRows);
      setScopeUnitId(current => unitRows.some(row => row.id === current) ? current : unitRows[0]?.id ?? "");
      setAccounts(accountRows);
      setAccountId(current => accountRows.some(row => row.id === current) ? current : accountRows[0]?.id ?? "");
    }).catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  async function preview() {
    if (!scopeUnitId) { setError("Select a geographical scope."); return; }
    if (view === "ACCOUNT_STATEMENT" && (!accountId || !from || !to || from > to)) {
      setError("Select an account and a valid statement date range."); return;
    }
    if (view === "TRIAL_BALANCE" && !asOf) { setError("Select the trial balance date."); return; }
    setBusy(true); setError(""); setTrialBalance(null); setStatement(null);
    const base = `/api/organisations/${organisationId}/financial-ledger`;
    try {
      if (view === "TRIAL_BALANCE") {
        const query = new URLSearchParams({ scopeUnitId, asOf });
        setTrialBalance(await request<TrialBalance>(`${base}/trial-balance?${query}`));
      } else {
        const query = new URLSearchParams({ scopeUnitId, accountId, from, to });
        setStatement(await request<Statement>(`${base}/account-statement?${query}`));
      }
    } catch (e) { setError(e instanceof Error ? e.message : "Ledger report could not be loaded."); }
    finally { setBusy(false); }
  }

  const money = (value: number, currency: string) => `${currency} ${Number(value ?? 0).toLocaleString("en-ZA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  return <div className="financial-reports">
    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">01</span><h2>General ledger reports</h2></div><span className="panel-tag">POSTED JOURNALS ONLY</span></div>
      <p className="panel-description">Ledger balances are calculated from posted double-entry journals only. Draft, submitted, approved-but-unposted and rejected journals are excluded. Results include the selected geographical unit and its descendants.</p>
      <div className="form-stack">
        <label>Report<select value={view} onChange={e => { setView(e.target.value as "TRIAL_BALANCE" | "ACCOUNT_STATEMENT"); setTrialBalance(null); setStatement(null); }}>
          <option value="TRIAL_BALANCE">Trial balance</option><option value="ACCOUNT_STATEMENT">Account statement</option>
        </select></label>
        <label>Geographical scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)}><option value="">Select scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
        {view === "TRIAL_BALANCE" ? <label>As at date<input type="date" value={asOf} onChange={e => setAsOf(e.target.value)} /></label> : <>
          <label>Financial account<select value={accountId} onChange={e => setAccountId(e.target.value)}><option value="">Select account</option>{accounts.map(account => <option key={account.id} value={account.id}>{account.code} — {account.name} ({account.currency})</option>)}</select></label>
          <div className="field-pair"><label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label><label>To<input type="date" value={to} onChange={e => setTo(e.target.value)} /></label></div>
        </>}
        {error && <div className="message error" role="alert">{error}</div>}
        <button className="primary-button" disabled={busy || !scopeUnitId} onClick={preview}>{busy ? "Loading…" : "Preview ledger report"}</button>
      </div>
    </section>
    {trialBalance && <section className="panel">
      <div className="panel-title"><div><span className="panel-index">02</span><h2>Trial balance</h2></div><span className="panel-tag">AS AT {trialBalance.asOf}</span></div>
      <div className="table-wrap"><table><thead><tr><th>ACCOUNT</th><th>TYPE</th><th>CURRENCY</th><th>TOTAL DEBITS</th><th>TOTAL CREDITS</th><th>DEBIT BALANCE</th><th>CREDIT BALANCE</th></tr></thead><tbody>
        {trialBalance.lines.map(row => <tr key={row.accountId}><td><strong>{row.accountCode}</strong><small className="table-subtext">{row.accountName}</small></td><td>{row.accountType}</td><td>{row.currency}</td><td>{money(row.totalDebits,row.currency)}</td><td>{money(row.totalCredits,row.currency)}</td><td>{money(row.balanceDebit,row.currency)}</td><td>{money(row.balanceCredit,row.currency)}</td></tr>)}
        {!trialBalance.lines.length && <tr><td colSpan={7} className="empty-table">No financial accounts are configured.</td></tr>}
      </tbody></table></div>
      <p className="panel-description">Debit and credit balances are displayed separately by currency. Account balances are derived from all posted journal lines dated on or before the selected date.</p>
    </section>}
    {statement && <section className="panel">
      <div className="panel-title"><div><span className="panel-index">02</span><h2>Account statement</h2></div><span className="panel-tag">{statement.from} TO {statement.to}</span></div>
      <p className="panel-description"><strong>{statement.account.accountCode} — {statement.account.accountName}</strong> · {statement.account.accountType} · {statement.account.currency}</p>
      <div className="overview-stat-grid">
        <article className="overview-stat"><span>OPENING DEBIT</span><strong>{money(statement.openingBalance.debit,statement.account.currency)}</strong></article>
        <article className="overview-stat"><span>OPENING CREDIT</span><strong>{money(statement.openingBalance.credit,statement.account.currency)}</strong></article>
        <article className="overview-stat"><span>PERIOD DEBITS</span><strong>{money(statement.periodDebits,statement.account.currency)}</strong></article>
        <article className="overview-stat"><span>PERIOD CREDITS</span><strong>{money(statement.periodCredits,statement.account.currency)}</strong></article>
        <article className="overview-stat"><span>CLOSING DEBIT</span><strong>{money(statement.closingBalance.debit,statement.account.currency)}</strong></article>
        <article className="overview-stat"><span>CLOSING CREDIT</span><strong>{money(statement.closingBalance.credit,statement.account.currency)}</strong></article>
      </div>
      <div className="table-wrap"><table><thead><tr><th>DATE</th><th>JOURNAL</th><th>DESCRIPTION</th><th>UNIT</th><th>DEBIT</th><th>CREDIT</th></tr></thead><tbody>
        {statement.entries.map((row,i) => <tr key={row.journalId + "-" + i}><td>{row.journalDate}</td><td>{row.journalNumber}</td><td><strong>{row.journalDescription}</strong>{row.lineDescription && <small className="table-subtext">{row.lineDescription}</small>}</td><td>{row.unitName}</td><td>{money(row.debitAmount,statement.account.currency)}</td><td>{money(row.creditAmount,statement.account.currency)}</td></tr>)}
        {!statement.entries.length && <tr><td colSpan={6} className="empty-table">No posted journal activity for this account in the selected period.</td></tr>}
      </tbody></table></div>
    </section>}
  </div>;
}
