import { useCallback, useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Account = { id: string; organisationId: string; code: string; name: string; accountType: string; currency: string; active: boolean };
type Unit = { id: string; level: string; code: string; name: string; active?: boolean };
type Mapping = { id: string; resolutionCode: string; currency: string; debitAccountId: string; debitAccountCode: string; debitAccountName: string; creditAccountId: string; creditAccountCode: string; creditAccountName: string };
type JournalSummary = { id: string; scopeUnitId: string; scopeName: string; journalNumber: string; journalDate: string; currency: string; description: string; status: string; debitTotal: number };
type Journal = { id: string; organisationId: string; scopeUnitId: string; journalNumber: string; journalDate: string; currency: string; description: string; status: string; createdBy: string; approvedBy: string | null; postedBy: string | null; lines: { id: string; accountId: string; accountCode: string; accountName: string; debitAmount: number; creditAmount: number; description: string | null }[] };

const classifications = [
  { value: "BANK_FEE", label: "Bank fee" },
  { value: "SHORT_DEPOSIT", label: "Short deposit" },
  { value: "OVER_DEPOSIT", label: "Over-deposit" },
  { value: "REFUND_OR_REVERSAL", label: "Refund or reversal" }
];
const money = (amount: number, currency: string) => new Intl.NumberFormat("en-ZA", { style: "currency", currency }).format(Number(amount || 0));

export default function FinancialJournals({ organisationId, authorizationHeader, canView, canCreate, canApprove }: {
  organisationId: string; authorizationHeader: string; canView: boolean; canCreate: boolean; canApprove: boolean;
}) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [units, setUnits] = useState<Unit[]>([]);
  const [mappings, setMappings] = useState<Mapping[]>([]);
  const [journals, setJournals] = useState<JournalSummary[]>([]);
  const [selected, setSelected] = useState<Journal | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [statusFilter, setStatusFilter] = useState("");
  const [debitAmount, setDebitAmount] = useState("");
  const [creditAmount, setCreditAmount] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  const request = useCallback(async <T,>(path: string, options?: RequestInit): Promise<T> => {
    const response = await fetch(`${apiBase}${path}`, {
      ...options,
      headers: { "Content-Type": "application/json", ...headers, ...options?.headers }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.status === 204 ? undefined as T : response.json() as Promise<T>;
  }, [apiBase, headers]);

  const reload = useCallback(async () => {
    if (!organisationId) return;
    const tasks: Promise<unknown>[] = [];
    if (canView) {
      tasks.push(request<Account[]>(`/api/organisations/${organisationId}/finance-accounts`).then(setAccounts));
      tasks.push(request<Mapping[]>(`/api/organisations/${organisationId}/finance-exception-account-mappings`).then(setMappings));
      tasks.push(request<JournalSummary[]>(`/api/organisations/${organisationId}/finance-journals${statusFilter ? `?status=${statusFilter}` : ""}`).then(setJournals));
    }
    if (canCreate) {
      tasks.push(request<Unit[]>(`/api/organisations/${organisationId}/units`).then(rows => setUnits(rows.filter(u => u.active !== false))));
    }
    await Promise.all(tasks);
  }, [organisationId, canView, canCreate, request, statusFilter]);

  useEffect(() => {
    setAccounts([]); setUnits([]); setMappings([]); setJournals([]); setSelected(null);
    setError(""); setNotice("");
    reload().catch((e: Error) => setError(e.message));
  }, [reload]);

  async function run(action: () => Promise<void>, success: string) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); await reload(); setNotice(success); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function createAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await run(async () => {
      await request(`/api/organisations/${organisationId}/finance-accounts`, { method: "POST", body: JSON.stringify({
        code: form.get("code"), name: form.get("name"), accountType: form.get("accountType"), currency: form.get("currency")
      }) });
      formElement.reset();
    }, "Financial account created.");
  }

  async function saveMapping(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await run(async () => {
      await request(`/api/organisations/${organisationId}/finance-exception-account-mappings`, { method: "PUT", body: JSON.stringify({
        resolutionCode: form.get("resolutionCode"), currency: "ZAR",
        debitAccountId: form.get("debitAccountId"), creditAccountId: form.get("creditAccountId")
      }) });
    }, "Reconciliation account mapping saved.");
  }

  async function createJournal(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const debit = Number(debitAmount), credit = Number(creditAmount);
    if (!Number.isFinite(debit) || !Number.isFinite(credit) || debit <= 0 || debit !== credit) {
      setError("Enter the same positive amount for the debit and credit lines.");
      return;
    }
    await run(async () => {
      await request(`/api/organisations/${organisationId}/finance-journals`, { method: "POST", body: JSON.stringify({
        journalNumber: form.get("journalNumber"), scopeUnitId: form.get("scopeUnitId"),
        journalDate: form.get("journalDate"), currency: form.get("currency"),
        description: form.get("description"), sourceType: "MANUAL", sourceId: null,
        lines: [
          { accountId: form.get("debitAccountId"), debitAmount: debit.toFixed(2), creditAmount: "0.00", description: form.get("debitDescription") || "Debit entry" },
          { accountId: form.get("creditAccountId"), debitAmount: "0.00", creditAmount: credit.toFixed(2), description: form.get("creditDescription") || "Credit entry" }
        ]
      }) });
      setDebitAmount(""); setCreditAmount("");
      formElement.reset();
    }, "Draft journal created. It must be submitted, independently approved and posted.");
  }

  async function openJournal(id: string) {
    setError("");
    try { setSelected(await request<Journal>(`/api/finance-journals/${id}`)); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not load journal."); }
  }

  async function journalAction(id: string, action: "submit" | "approve" | "post" | "reject") {
    const reason = action === "reject" ? window.prompt("Enter the reason for rejecting this journal:") : null;
    if (action === "reject" && (!reason || !reason.trim())) return;
    await run(async () => {
      await request(`/api/finance-journals/${id}/${action}`, {
        method: "POST",
        ...(action === "reject" ? { body: JSON.stringify({ reason }) } : {})
      });
      const updated = await request<Journal>(`/api/finance-journals/${id}`);
      setSelected(updated);
    }, `Journal ${action === "submit" ? "submitted" : action === "approve" ? "approved" : action === "post" ? "posted" : "rejected"}.`);
  }

  async function toggleAccount(account: Account) {
    await run(async () => {
      await request(`/api/finance-accounts/${account.id}`, {
        method: "PATCH", body: JSON.stringify({ name: account.name, active: !account.active })
      });
    }, `Account ${account.active ? "deactivated" : "reactivated"}.`);
  }

  const activeAccounts = accounts.filter(a => a.active && a.currency === "ZAR");
  const totals = selected?.lines.reduce((acc, line) => ({
    debit: acc.debit + Number(line.debitAmount), credit: acc.credit + Number(line.creditAmount)
  }), { debit: 0, credit: 0 }) ?? { debit: 0, credit: 0 };

  if (!organisationId) return <section className="panel"><h2>Financial accounts &amp; journals</h2><p>Select an organisation first.</p></section>;

  return <section className="panel financial-journals">
    <div className="panel-title"><div><span className="panel-index">08</span><h2>Financial accounts &amp; journals</h2></div><span className="panel-tag">DOUBLE-ENTRY CONTROL</span></div>
    <p className="panel-description">Maintain the organisation's chart of accounts, configure reconciliation adjustment mappings and operate the independently controlled journal lifecycle.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}

    {canCreate && <>
      <div className="journal-section-heading"><div><h3>Chart of accounts</h3><p>Account codes and classifications are organisation-specific. Accounts already used in journals cannot be renamed.</p></div><span>{accounts.length} ACCOUNTS</span></div>
      <form className="form-stack journal-form" onSubmit={createAccount}>
        <div className="field-pair"><label>Account code<input name="code" required maxLength={40} pattern="[A-Za-z0-9_-]{2,40}" placeholder="e.g. 1000" /></label><label>Account name<input name="name" required maxLength={120} placeholder="e.g. Cash at bank" /></label></div>
        <div className="field-pair"><label>Account type<select name="accountType" defaultValue="ASSET"><option value="ASSET">Asset</option><option value="LIABILITY">Liability</option><option value="EQUITY">Equity</option><option value="INCOME">Income</option><option value="EXPENSE">Expense</option></select></label><label>Currency<select name="currency" defaultValue="ZAR"><option value="ZAR">ZAR — South African Rand</option><option value="USD">USD — US Dollar</option><option value="ZWL">ZWL — Zimbabwean Dollar</option></select></label></div>
        <button className="primary-button" disabled={busy}>Create account</button>
      </form>
    </>}
    <div className="table-wrap"><table><thead><tr><th>CODE</th><th>ACCOUNT</th><th>TYPE</th><th>CURRENCY</th><th>STATUS</th>{canCreate && <th></th>}</tr></thead><tbody>
      {accounts.map(account => <tr key={account.id}><td><strong>{account.code}</strong></td><td>{account.name}</td><td>{account.accountType}</td><td>{account.currency}</td><td><span className={account.active ? "active-label" : "inactive-label"}>{account.active ? "ACTIVE" : "INACTIVE"}</span></td>{canCreate && <td><button className="secondary-button" type="button" disabled={busy} onClick={() => toggleAccount(account)}>{account.active ? "Deactivate" : "Reactivate"}</button></td>}</tr>)}
      {!accounts.length && <tr><td colSpan={canCreate ? 6 : 5} className="empty-table">No financial accounts configured.</td></tr>}
    </tbody></table></div>

    {canCreate && canView && <>
      <div className="panel-divider" />
      <div className="journal-section-heading"><div><h3>Reconciliation account mappings</h3><p>Choose the debit and credit accounts used when a classified bank reconciliation exception generates a draft adjustment.</p></div><span>4 CLASSIFICATIONS</span></div>
      <form className="form-stack journal-form" onSubmit={saveMapping}>
        <div className="field-pair"><label>Classification<select name="resolutionCode" required defaultValue=""><option value="">Select classification</option>{classifications.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}</select></label><label>Currency<input value="ZAR" readOnly /></label></div>
        <div className="field-pair"><label>Debit account<select name="debitAccountId" required defaultValue=""><option value="">Select debit account</option>{activeAccounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label><label>Credit account<select name="creditAccountId" required defaultValue=""><option value="">Select credit account</option>{activeAccounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label></div>
        <button className="primary-button" disabled={busy || activeAccounts.length < 2}>Save account mapping</button>
      </form>
    </>}
    <div className="table-wrap"><table><thead><tr><th>CLASSIFICATION</th><th>DEBIT ACCOUNT</th><th>CREDIT ACCOUNT</th><th>CURRENCY</th></tr></thead><tbody>
      {mappings.map(m => <tr key={m.id}><td><strong>{classifications.find(c => c.value === m.resolutionCode)?.label ?? m.resolutionCode}</strong></td><td>{m.debitAccountCode} — {m.debitAccountName}</td><td>{m.creditAccountCode} — {m.creditAccountName}</td><td>{m.currency}</td></tr>)}
      {!mappings.length && <tr><td colSpan={4} className="empty-table">No reconciliation account mappings configured.</td></tr>}
    </tbody></table></div>

    {canCreate && canView && <>
      <div className="panel-divider" />
      <div className="journal-section-heading"><div><h3>Manual journal entry</h3><p>Create a two-line balanced draft. Submission, approval and posting remain separate actions.</p></div><span>DRAFT ONLY</span></div>
      <form className="form-stack journal-form" onSubmit={createJournal}>
        <div className="field-pair"><label>Journal number<input name="journalNumber" required maxLength={80} placeholder="e.g. JV-2026-001" /></label><label>Journal date<input name="journalDate" type="date" required defaultValue={new Date().toISOString().slice(0,10)} /></label></div>
        <label>Financial scope<select name="scopeUnitId" required defaultValue=""><option value="">Select geographical unit</option>{units.map(u => <option key={u.id} value={u.id}>{u.level} · {u.code} — {u.name}</option>)}</select></label>
        <div className="field-pair"><label>Currency<select name="currency" defaultValue="ZAR"><option value="ZAR">ZAR</option><option value="USD">USD</option><option value="ZWL">ZWL</option></select></label><label>Amount<input type="number" min="0.01" step="0.01" required value={debitAmount} onChange={e => {setDebitAmount(e.target.value);setCreditAmount(e.target.value);}} placeholder="0.00" /></label></div>
        <div className="field-pair"><label>Debit account<select name="debitAccountId" required defaultValue=""><option value="">Select debit account</option>{accounts.filter(a => a.active).map(a => <option key={a.id} value={a.id}>{a.code} — {a.name} ({a.currency})</option>)}</select></label><label>Credit account<select name="creditAccountId" required defaultValue=""><option value="">Select credit account</option>{accounts.filter(a => a.active).map(a => <option key={a.id} value={a.id}>{a.code} — {a.name} ({a.currency})</option>)}</select></label></div>
        <div className="field-pair"><label>Debit line description<input name="debitDescription" maxLength={500} placeholder="Debit entry" /></label><label>Credit line description<input name="creditDescription" maxLength={500} placeholder="Credit entry" /></label></div>
        <label>Journal description<input name="description" required maxLength={1000} placeholder="Reason for this adjustment" /></label>
        <button className="primary-button" disabled={busy || !accounts.some(a => a.active) || !units.length}>Create draft journal</button>
      </form>
    </>}

    {!canView && <div className="legacy-record-notice"><strong>VIEW PERMISSION REQUIRED</strong><span>Chart of accounts, mappings and journal registers require FINANCE_JOURNAL_VIEW. Account creation remains available to authorised creators.</span></div>}
    {canView && <>
    <div className="panel-divider" />
    <div className="journal-section-heading"><div><h3>Journal register</h3><p>Filter journals by lifecycle status and inspect their debit and credit lines.</p></div><label className="journal-filter">STATUS<select value={statusFilter} onChange={e => setStatusFilter(e.target.value)}><option value="">All statuses</option>{["DRAFT","SUBMITTED","APPROVED","POSTED","REJECTED"].map(s => <option key={s} value={s}>{s}</option>)}</select></label></div>
    <div className="table-wrap"><table><thead><tr><th>JOURNAL</th><th>DATE</th><th>SCOPE</th><th>DESCRIPTION</th><th>STATUS</th><th>AMOUNT</th><th></th></tr></thead><tbody>
      {journals.map(j => <tr key={j.id}><td><strong>{j.journalNumber}</strong></td><td>{j.journalDate}</td><td>{j.scopeName}</td><td>{j.description}</td><td><span className={j.status === "POSTED" ? "active-label" : j.status === "REJECTED" ? "inactive-label" : "classification-code"}>{j.status}</span></td><td>{money(j.debitTotal,j.currency)}</td><td><button className="secondary-button" type="button" onClick={() => openJournal(j.id)}>View</button></td></tr>)}
      {!journals.length && <tr><td colSpan={7} className="empty-table">No journals match this filter.</td></tr>}
    </tbody></table></div>

    {selected && <section className="journal-detail">
      <div className="journal-section-heading"><div><h3>{selected.journalNumber}</h3><p>{selected.description} · {selected.journalDate}</p></div><button type="button" className="secondary-button" onClick={() => setSelected(null)}>Close</button></div>
      <div className="overview-stat-grid"><article className="overview-stat"><span>DEBITS</span><strong>{money(totals.debit,selected.currency)}</strong></article><article className="overview-stat"><span>CREDITS</span><strong>{money(totals.credit,selected.currency)}</strong></article><article className="overview-stat"><span>STATUS</span><strong>{selected.status}</strong></article></div>
      <div className="table-wrap"><table><thead><tr><th>ACCOUNT</th><th>DESCRIPTION</th><th>DEBIT</th><th>CREDIT</th></tr></thead><tbody>{selected.lines.map(line => <tr key={line.id}><td><strong>{line.accountCode} — {line.accountName}</strong></td><td>{line.description || "—"}</td><td>{money(line.debitAmount,selected.currency)}</td><td>{money(line.creditAmount,selected.currency)}</td></tr>)}</tbody></table></div>
      <div className="journal-actions">
        {canCreate && selected.status === "DRAFT" && <button className="primary-button" disabled={busy} onClick={() => journalAction(selected.id,"submit")}>Submit for approval</button>}
        {canApprove && selected.status === "SUBMITTED" && <><button className="primary-button" disabled={busy} onClick={() => journalAction(selected.id,"approve")}>Approve</button><button className="secondary-button" disabled={busy} onClick={() => journalAction(selected.id,"reject")}>Reject</button></>}
        {canApprove && selected.status === "APPROVED" && <button className="primary-button" disabled={busy} onClick={() => journalAction(selected.id,"post")}>Post journal</button>}
      </div>
      <p className="panel-description">The API enforces separation of duties: the creator cannot approve or reject their own journal, and the creator and approver cannot post the same journal.</p>
    </section>}
    </>}
  </section>;
}
