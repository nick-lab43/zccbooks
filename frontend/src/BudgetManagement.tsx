import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; level: string; code: string; name: string };
type Category = { id: string; code: string; name: string; active: boolean };
type Budget = {
  id: string; unitId: string; unitLevel: string; unitCode: string; unitName: string;
  categoryId: string; categoryCode: string; categoryName: string; periodStart: string; periodEnd: string;
  budgetAmount: number; actualAmount: number; remainingAmount: number; utilisationPercentage: number;
  currency: string; note: string | null; version: number;
};
type Revision = {
  id: string; previousAmount: number; revisedAmount: number; previousVersion: number;
  newVersion: number; reason: string; revisedBy: string; revisedAt: string;
};

export default function BudgetManagement({ organisationId, authorizationHeader, canManage }: {
  organisationId: string; authorizationHeader: string; canManage: boolean;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [revisions, setRevisions] = useState<Record<string, Revision[]>>({});
  const [expanded, setExpanded] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, {
      ...options,
      headers: { "Content-Type": "application/json", ...headers, ...options?.headers }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.status === 204 ? undefined as T : response.json() as Promise<T>;
  }

  async function reload() {
    if (!organisationId || !scopeUnitId) return;
    const rows = await request<Budget[]>(`/api/organisations/${organisationId}/expenditure-budgets?scopeUnitId=${scopeUnitId}`);
    setBudgets(rows);
  }

  useEffect(() => {
    setUnits([]); setCategories([]); setBudgets([]); setRevisions({});
    setScopeUnitId(""); setError(""); setNotice("");
    if (!organisationId) return;
    request<Unit[]>(`/api/organisations/${organisationId}/financial-reports/units`)
      .then(rows => {
        setUnits(rows);
        setScopeUnitId(rows[0]?.id ?? "");
      })
      .catch((e: Error) => setError(e.message));
    if (canManage) {
      Promise.all([
        request<Unit[]>(`/api/organisations/${organisationId}/expenditure-units`),
        request<Category[]>(`/api/organisations/${organisationId}/expenditure-categories`)
      ]).then(([unitRows, categoryRows]) => {
        setUnits(unitRows);
        setCategories(categoryRows);
        setScopeUnitId(current => current || unitRows[0]?.id || "");
      }).catch((e: Error) => setError(e.message));
    }
  }, [organisationId, authorizationHeader, canManage]);

  useEffect(() => {
    if (scopeUnitId) reload().catch((e: Error) => setError(e.message));
  }, [scopeUnitId, organisationId, authorizationHeader]);

  async function createBudget(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setBusy(true); setError(""); setNotice("");
    try {
      await request(`/api/organisations/${organisationId}/expenditure-budgets`, {
        method: "POST",
        body: JSON.stringify({
          unitId: form.get("unitId"), categoryId: form.get("categoryId"),
          periodStart: form.get("periodStart"), periodEnd: form.get("periodEnd"),
          budgetAmount: form.get("budgetAmount"), currency: form.get("currency"),
          note: form.get("note")
        })
      });
      await reload();
      formElement.reset();
      setNotice("Budget allocation created.");
    } catch (e) { setError(e instanceof Error ? e.message : "Budget could not be created."); }
    finally { setBusy(false); }
  }

  async function reviseBudget(event: FormEvent<HTMLFormElement>, budget: Budget) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    try {
      await request(`/api/expenditure-budgets/${budget.id}/revisions`, {
        method: "POST",
        body: JSON.stringify({
          revisedAmount: form.get("revisedAmount"), expectedVersion: budget.version,
          reason: form.get("reason")
        })
      });
      await reload();
      setNotice(`Budget revised. New version: ${budget.version + 1}.`);
    } catch (e) { setError(e instanceof Error ? e.message : "Budget revision failed."); }
    finally { setBusy(false); }
  }

  async function toggleHistory(budget: Budget) {
    if (expanded === budget.id) { setExpanded(null); return; }
    setExpanded(budget.id);
    if (revisions[budget.id]) return;
    try {
      const rows = await request<Revision[]>(`/api/expenditure-budgets/${budget.id}/revisions`);
      setRevisions(current => ({ ...current, [budget.id]: rows }));
    } catch (e) { setError(e instanceof Error ? e.message : "Revision history could not be loaded."); }
  }

  const totals = budgets.reduce((result, row) => {
    const key = row.currency;
    result[key] ??= { budget: 0, actual: 0 };
    result[key].budget += Number(row.budgetAmount);
    result[key].actual += Number(row.actualAmount);
    return result;
  }, {} as Record<string, { budget: number; actual: number }>);

  return <div className="financial-administration">
    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">01</span><h2>Budget performance</h2></div><span className="panel-tag">BUDGET VS ACTUAL</span></div>
      <p className="panel-description">Compare approved and paid expenditure against authorised budgets. Actuals include expenses recorded in the budget unit and its descendants, within the budget period. Draft, submitted and rejected expenses are excluded.</p>
      <label>Geographical scope<select value={scopeUnitId} onChange={event => setScopeUnitId(event.target.value)}>
        <option value="">Select scope</option>
        {units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}
      </select></label>
      {(error || notice) && <div className={error ? "message error" : "message success"} role="status">{error || notice}</div>}
      <div className="overview-stat-grid">
        {Object.entries(totals).map(([currency, total]) => <article className="overview-stat" key={currency}>
          <span>ALLOCATED · {currency}</span><strong>{currency} {total.budget.toLocaleString("en-ZA", { minimumFractionDigits: 2 })}</strong>
          <small className="table-subtext">Actual: {currency} {total.actual.toLocaleString("en-ZA", { minimumFractionDigits: 2 })}</small>
        </article>)}
        {!Object.keys(totals).length && <article className="overview-stat"><span>BUDGET LINES</span><strong>00</strong></article>}
      </div>
    </section>

    {canManage && <section className="panel">
      <div className="panel-title"><div><span className="panel-index">02</span><h2>Create budget allocation</h2></div><span className="panel-tag">CENTRAL AUTHORISATION</span></div>
      <form className="form-stack" onSubmit={createBudget}>
        <div className="field-pair">
          <label>Geographical unit<select name="unitId" required defaultValue=""><option value="">Select unit</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
          <label>Expenditure category<select name="categoryId" required defaultValue=""><option value="">Select category</option>{categories.filter(category => category.active).map(category => <option key={category.id} value={category.id}>{category.name} ({category.code})</option>)}</select></label>
        </div>
        <div className="field-pair">
          <label>Period start<input name="periodStart" type="date" required /></label>
          <label>Period end<input name="periodEnd" type="date" required /></label>
        </div>
        <div className="field-pair">
          <label>Budget amount<input name="budgetAmount" type="number" min="0.01" step="0.01" required /></label>
          <label>Currency<select name="currency" defaultValue="ZAR"><option value="ZAR">ZAR</option><option value="USD">USD</option><option value="EUR">EUR</option><option value="GBP">GBP</option></select></label>
        </div>
        <label>Allocation note<input name="note" maxLength={1000} placeholder="Purpose or approval reference" /></label>
        <button className="primary-button" disabled={busy || !categories.some(category => category.active)}>Create budget</button>
      </form>
      <p className="panel-description">Overlapping budgets for the same category and currency are blocked across the same unit and its parent/child units. Budget changes are recorded as versioned amendments.</p>
    </section>}

    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">03</span><h2>Budget register</h2></div><span className="panel-tag">{budgets.length} ALLOCATIONS</span></div>
      <div className="table-wrap"><table><thead><tr><th>PERIOD / UNIT</th><th>CATEGORY</th><th>BUDGET</th><th>ACTUAL</th><th>REMAINING</th><th>UTILISATION</th><th>HISTORY</th></tr></thead><tbody>
        {budgets.map(budget => <tr key={budget.id}>
          <td><strong>{budget.periodStart} — {budget.periodEnd}</strong><small className="table-subtext">{budget.unitLevel} · {budget.unitCode} — {budget.unitName}</small></td>
          <td>{budget.categoryName}<small className="table-subtext">Version {budget.version}</small></td>
          <td>{budget.currency} {Number(budget.budgetAmount).toLocaleString("en-ZA", { minimumFractionDigits: 2 })}</td>
          <td>{budget.currency} {Number(budget.actualAmount).toLocaleString("en-ZA", { minimumFractionDigits: 2 })}</td>
          <td><strong>{budget.currency} {Number(budget.remainingAmount).toLocaleString("en-ZA", { minimumFractionDigits: 2 })}</strong></td>
          <td><strong>{Number(budget.utilisationPercentage).toFixed(2)}%</strong><small className="table-subtext">{budget.actualAmount > budget.budgetAmount ? "OVER BUDGET" : "WITHIN BUDGET"}</small></td>
          <td><button type="button" className="secondary-button" onClick={() => toggleHistory(budget)}> {expanded === budget.id ? "Hide" : "View"} </button></td>
        </tr>)}
        {!budgets.length && <tr><td colSpan={7} className="empty-table">No budget allocations exist within this scope.</td></tr>}
      </tbody></table></div>
      {budgets.map(budget => expanded === budget.id && <div className="panel-divider" key={budget.id}>
        <div className="panel-title"><div><span className="panel-index">HISTORY</span><h2>{budget.categoryName} · {budget.currency}</h2></div><span className="panel-tag">VERSION {budget.version}</span></div>
        {canManage && <form className="form-stack" onSubmit={event => reviseBudget(event, budget)}>
          <div className="field-pair"><label>Revised budget amount<input name="revisedAmount" type="number" min="0.01" step="0.01" required defaultValue={budget.budgetAmount} /></label><label>Reason for amendment<input name="reason" required maxLength={1000} placeholder="Explain why the allocation is changing" /></label></div>
          <button className="secondary-button" disabled={busy}>Save budget revision</button>
        </form>}
        <div className="table-wrap"><table><thead><tr><th>VERSION</th><th>PREVIOUS</th><th>REVISED</th><th>REASON</th><th>REVISED BY</th><th>DATE</th></tr></thead><tbody>
          {(revisions[budget.id] ?? []).map(revision => <tr key={revision.id}><td>{revision.previousVersion} → {revision.newVersion}</td><td>{budget.currency} {Number(revision.previousAmount).toFixed(2)}</td><td>{budget.currency} {Number(revision.revisedAmount).toFixed(2)}</td><td>{revision.reason}</td><td>{revision.revisedBy}</td><td>{new Date(revision.revisedAt).toLocaleString()}</td></tr>)}
          {!(revisions[budget.id] ?? []).length && <tr><td colSpan={6} className="empty-table">No amendments recorded.</td></tr>}
        </tbody></table></div>
      </div>)}
    </section>
  </div>;
}
