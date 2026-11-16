import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import ExpenditureEvidence from "./ExpenditureEvidence";

type Unit = { id: string; level: string; code: string; name: string };
type Category = { id: string; code: string; name: string; description: string | null; active: boolean };
type Expense = {
  id: string; scopeUnitId: string; scopeName: string; categoryId: string; categoryName: string;
  expenseDate: string; payee: string; description: string; reference: string | null;
  amount: number; currency: string; status: string; capturedBy: string; createdAt: string;
  submittedAt: string | null; reviewedBy: string | null; reviewedAt: string | null;
  reviewNote: string | null; paidAt: string | null; paymentReference: string | null;
  version: number; voidedBy: string | null; voidedAt: string | null; voidReason: string | null;
};

export default function ExpenditureManagement({ organisationId, authorizationHeader, canCapture, canApprove }: {
  organisationId: string; authorizationHeader: string; canCapture: boolean; canApprove: boolean;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [expenses, setExpenses] = useState<Expense[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reviewNotes, setReviewNotes] = useState<Record<string, string>>({});
  const [paymentReferences, setPaymentReferences] = useState<Record<string, string>>({});
  const [voidReasons, setVoidReasons] = useState<Record<string, string>>({});
  const [editingDraft, setEditingDraft] = useState<Expense | null>(null);
  const [draftForm, setDraftForm] = useState({ categoryId: "", expenseDate: "", payee: "", description: "", reference: "", amount: "", currency: "ZAR" });
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, {
      ...options, headers: { "Content-Type": "application/json", ...headers, ...options?.headers }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.status === 204 ? undefined as T : response.json() as Promise<T>;
  }

  async function loadExpenses(unitId = scopeUnitId) {
    if (!unitId) { setExpenses([]); return; }
    const rows = await request<Expense[]>(`/api/organisations/${organisationId}/expenditures?scopeUnitId=${unitId}`);
    setExpenses(rows);
  }

  useEffect(() => {
    if (!organisationId) return;
    let active = true;
    Promise.all([
      request<Unit[]>(`/api/organisations/${organisationId}/expenditure-units`),
      request<Category[]>(`/api/organisations/${organisationId}/expenditure-categories`)
    ]).then(([unitRows, categoryRows]) => {
      if (!active) return;
      setUnits(unitRows);
      setCategories(categoryRows);
      setScopeUnitId(current => unitRows.some(unit => unit.id === current) ? current : unitRows[0]?.id ?? "");
    }).catch((e: Error) => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [organisationId, authorizationHeader]);

  useEffect(() => {
    if (!scopeUnitId) return;
    loadExpenses(scopeUnitId).catch((e: Error) => setError(e.message));
  }, [scopeUnitId, organisationId, authorizationHeader]);

  async function perform(action: () => Promise<void>, success: string) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); await loadExpenses(); setNotice(success); }
    catch (e) { setError(e instanceof Error ? e.message : "The expenditure action failed."); }
    finally { setBusy(false); }
  }

  async function createExpense(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await perform(async () => {
      await request(`/api/organisations/${organisationId}/expenditures`, {
        method: "POST",
        body: JSON.stringify({
          scopeUnitId, categoryId: String(form.get("categoryId")), expenseDate: String(form.get("expenseDate")),
          payee: String(form.get("payee")), description: String(form.get("description")),
          reference: String(form.get("reference") || ""), amount: Number(form.get("amount")),
          currency: String(form.get("currency") || "ZAR")
        })
      });
      formElement.reset();
    }, "Draft expenditure recorded.");
  }

  function beginDraftEdit(expense: Expense) {
    setEditingDraft(expense);
    setDraftForm({ categoryId: expense.categoryId, expenseDate: expense.expenseDate, payee: expense.payee,
      description: expense.description, reference: expense.reference ?? "", amount: String(expense.amount), currency: expense.currency });
  }

  async function saveDraftEdit() {
    if (!editingDraft) return;
    await perform(async () => {
      await request(`/api/expenditures/${editingDraft.id}`, {
        method: "PATCH",
        body: JSON.stringify({ ...draftForm, amount: Number(draftForm.amount), version: editingDraft.version })
      });
      setEditingDraft(null);
    }, "Draft expenditure amended. The new version is recorded.");
  }

  async function voidDraft(expense: Expense) {
    const reason = voidReasons[expense.id] ?? "";
    if (!reason.trim()) { setError("A reason is required to withdraw a draft."); return; }
    await perform(async () => {
      await request(`/api/expenditures/${expense.id}/void`, {
        method: "POST", body: JSON.stringify({ reason })
      });
      setVoidReasons(rows => { const next = { ...rows }; delete next[expense.id]; return next; });
    }, "Draft expenditure withdrawn and retained in the audit register.");
  }

  async function submitExpense(expense: Expense) {
    await perform(async () => {
      await request(`/api/expenditures/${expense.id}/submit`, { method: "POST" });
    }, "Expenditure submitted for independent approval.");
  }

  async function reviewExpense(expense: Expense, decision: "APPROVE" | "REJECT") {
    const note = reviewNotes[expense.id] ?? "";
    if (decision === "REJECT" && !note.trim()) { setError("A rejection reason is required."); return; }
    await perform(async () => {
      await request(`/api/expenditures/${expense.id}/review`, {
        method: "POST", body: JSON.stringify({ decision, note })
      });
      setReviewNotes(rows => { const next = { ...rows }; delete next[expense.id]; return next; });
    }, decision === "APPROVE" ? "Expenditure approved." : "Expenditure rejected.");
  }

  async function markPaid(expense: Expense) {
    const paymentReference = paymentReferences[expense.id] ?? "";
    if (!paymentReference.trim()) { setError("Enter the payment reference before marking this expense as paid."); return; }
    await perform(async () => {
      await request(`/api/expenditures/${expense.id}/pay`, {
        method: "POST", body: JSON.stringify({ paymentReference })
      });
      setPaymentReferences(rows => { const next = { ...rows }; delete next[expense.id]; return next; });
    }, "Payment recorded.");
  }

  async function createCategory(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await perform(async () => {
      const created = await request<Category>(`/api/organisations/${organisationId}/expenditure-categories`, {
        method: "POST", body: JSON.stringify({
          code: String(form.get("code")), name: String(form.get("name")), description: String(form.get("description") || "")
        })
      });
      setCategories(rows => [...rows, created].sort((a, b) => a.name.localeCompare(b.name)));
      formElement.reset();
    }, "Expenditure category created.");
  }

  const totalByCurrency = expenses.filter(row => row.status === "APPROVED" || row.status === "PAID")
    .reduce<Record<string, number>>((totals, row) => {
      totals[row.currency] = (totals[row.currency] ?? 0) + Number(row.amount);
      return totals;
    }, {});

  return <div className="financial-administration">
    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">01</span><h2>Expenditure workspace</h2></div><span className="panel-tag">SCOPE CONTROLLED</span></div>
      <p className="panel-description">Record church spending against an authorised geographical unit. Submitted expenses require approval by a different administrator before they can be marked as paid.</p>
      <label>Geographical scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)}>
        <option value="">Select scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}
      </select></label>
      {(error || notice) && <div className={error ? "message error" : "message success"} role="status">{error || notice}</div>}
      <div className="overview-stat-grid">
        <article className="overview-stat"><span>EXPENDITURE RECORDS</span><strong>{expenses.length}</strong></article>
        <article className="overview-stat"><span>AWAITING APPROVAL</span><strong>{expenses.filter(row => row.status === "SUBMITTED").length}</strong></article>
        <article className="overview-stat"><span>APPROVED / PAID</span><strong>{expenses.filter(row => row.status === "APPROVED" || row.status === "PAID").length}</strong></article>
        {Object.entries(totalByCurrency).map(([currency, total]) => <article className="overview-stat" key={currency}><span>APPROVED EXPENDITURE · {currency}</span><strong>{currency} {total.toLocaleString("en-ZA", { minimumFractionDigits: 2 })}</strong></article>)}
      </div>
    </section>

    {canCapture && <section className="panel">
      <div className="panel-title"><div><span className="panel-index">02</span><h2>Record expenditure</h2></div><span className="panel-tag">DRAFT</span></div>
      <form className="form-stack" onSubmit={createExpense}>
        <div className="field-pair">
          <label>Expense date<input name="expenseDate" type="date" required defaultValue={new Date().toISOString().slice(0, 10)} /></label>
          <label>Category<select name="categoryId" required defaultValue=""><option value="">Select category</option>{categories.filter(item => item.active).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        </div>
        <div className="field-pair">
          <label>Payee / supplier<input name="payee" required maxLength={160} placeholder="Supplier or recipient" /></label>
          <label>Invoice / reference<input name="reference" maxLength={120} placeholder="Optional reference" /></label>
        </div>
        <label>Description<textarea name="description" required maxLength={1000} rows={3} placeholder="What was purchased or paid for, and why?" /></label>
        <div className="field-pair">
          <label>Amount<input name="amount" type="number" min="0.01" step="0.01" required /></label>
          <label>Currency<select name="currency" defaultValue="ZAR"><option value="ZAR">ZAR</option><option value="USD">USD</option><option value="EUR">EUR</option><option value="GBP">GBP</option></select></label>
        </div>
        <button className="primary-button" disabled={busy || !scopeUnitId || !categories.some(item => item.active)}>Save draft expenditure</button>
      </form>
    </section>}

    {canApprove && <section className="panel">
      <div className="panel-title"><div><span className="panel-index">03</span><h2>Expenditure categories</h2></div><span className="panel-tag">CENTRAL CONFIGURATION</span></div>
      <form className="form-stack" onSubmit={createCategory}>
        <div className="field-pair"><label>Category code<input name="code" required maxLength={40} pattern="[A-Za-z0-9_-]{2,40}" placeholder="e.g. UTILITIES" /></label><label>Category name<input name="name" required maxLength={100} placeholder="Utilities" /></label></div>
        <label>Description<input name="description" maxLength={300} /></label>
        <button className="secondary-button" disabled={busy}>Add expenditure category</button>
      </form>
      <div className="classification-list">{categories.map(item => <div className="classification-row" key={item.id}><span className="classification-code">{item.code}</span><span>{item.name}</span><span className="active-label">{item.active ? "ACTIVE" : "INACTIVE"}</span></div>)}</div>
    </section>}

    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">04</span><h2>Expenditure register</h2></div><span className="panel-tag">{expenses.length} RECORDS</span></div>
      <div className="table-wrap"><table><thead><tr><th>DATE / PAYEE</th><th>CATEGORY / DETAILS</th><th>AMOUNT</th><th>STATUS</th><th>CONTROL / ACTION</th></tr></thead><tbody>
        {expenses.map(expense => <tr key={expense.id}>
          <td><strong>{expense.expenseDate} · {expense.payee}</strong><small className="table-subtext">{expense.reference || expense.scopeName}</small></td>
          <td>{expense.categoryName}<small className="table-subtext">{expense.description}</small></td>
          <td>{expense.currency} {Number(expense.amount).toLocaleString("en-ZA", { minimumFractionDigits: 2 })}</td>
          <td><strong>{expense.status}</strong>{expense.reviewNote && <small className="table-subtext">{expense.reviewNote}</small>}</td>
          <td>
            <ExpenditureEvidence expenseId={expense.id} authorizationHeader={authorizationHeader} canCapture={canCapture} locked={expense.status === "PAID" || expense.status === "VOIDED"} />
            {canCapture && expense.status === "DRAFT" && <div className="form-stack">
              {editingDraft?.id === expense.id ? <div className="form-stack">
                <label>Expense date<input type="date" value={draftForm.expenseDate} onChange={e => setDraftForm(row => ({ ...row, expenseDate: e.target.value }))} /></label>
                <label>Category<select value={draftForm.categoryId} onChange={e => setDraftForm(row => ({ ...row, categoryId: e.target.value }))}>{categories.filter(item => item.active).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
                <label>Payee<input value={draftForm.payee} maxLength={160} onChange={e => setDraftForm(row => ({ ...row, payee: e.target.value }))} /></label>
                <label>Description<textarea rows={2} maxLength={1000} value={draftForm.description} onChange={e => setDraftForm(row => ({ ...row, description: e.target.value }))} /></label>
                <label>Reference<input value={draftForm.reference} maxLength={120} onChange={e => setDraftForm(row => ({ ...row, reference: e.target.value }))} /></label>
                <div className="field-pair"><label>Amount<input type="number" min="0.01" step="0.01" value={draftForm.amount} onChange={e => setDraftForm(row => ({ ...row, amount: e.target.value }))} /></label>
                  <label>Currency<select value={draftForm.currency} onChange={e => setDraftForm(row => ({ ...row, currency: e.target.value }))}><option>ZAR</option><option>USD</option><option>EUR</option><option>GBP</option></select></label></div>
                <div className="field-pair"><button className="secondary-button" disabled={busy} onClick={saveDraftEdit}>Save amendment</button><button className="secondary-button" disabled={busy} onClick={() => setEditingDraft(null)}>Cancel</button></div>
              </div> : <button className="secondary-button" disabled={busy} onClick={() => beginDraftEdit(expense)}>Edit draft</button>}
              <button className="secondary-button" disabled={busy} onClick={() => submitExpense(expense)}>Submit for approval</button>
              <input aria-label="Draft withdrawal reason" value={voidReasons[expense.id] ?? ""} maxLength={500} placeholder="Reason for withdrawing draft" onChange={e => setVoidReasons(rows => ({ ...rows, [expense.id]: e.target.value }))} />
              <button className="secondary-button" disabled={busy || !(voidReasons[expense.id] ?? "").trim()} onClick={() => voidDraft(expense)}>Withdraw draft</button>
            </div>}
            {canApprove && expense.status === "SUBMITTED" && <div className="form-stack"><input aria-label="Review note" value={reviewNotes[expense.id] ?? ""} maxLength={500} placeholder="Reason required for rejection" onChange={e => setReviewNotes(rows => ({ ...rows, [expense.id]: e.target.value }))} /><div className="field-pair"><button className="secondary-button" disabled={busy} onClick={() => reviewExpense(expense, "APPROVE")}>Approve</button><button className="secondary-button" disabled={busy || !(reviewNotes[expense.id] ?? "").trim()} onClick={() => reviewExpense(expense, "REJECT")}>Reject</button></div></div>}
            {canApprove && expense.status === "APPROVED" && <div className="form-stack"><input aria-label="Payment reference" value={paymentReferences[expense.id] ?? ""} maxLength={120} placeholder="Payment reference" onChange={e => setPaymentReferences(rows => ({ ...rows, [expense.id]: e.target.value }))} /><button className="secondary-button" disabled={busy || !(paymentReferences[expense.id] ?? "").trim()} onClick={() => markPaid(expense)}>Mark paid</button></div>}
          </td>
        </tr>)}
        {!expenses.length && <tr><td colSpan={5} className="empty-table">No expenditure records exist for this scope.</td></tr>}
      </tbody></table></div>
    </section>
  </div>;
}
