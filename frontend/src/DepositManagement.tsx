import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Receipt = { id: string; receiptNumber: string; memberName: string; amount: number; contributionDate: string; paymentMethod: string; paymentReference: string | null; categoryName: string; scopeUnitId: string };
type Batch = { id: string; scopeUnitId: string; scopeName: string; depositDate: string; bankName: string; bankReference: string; expectedAmount: number; status: string; note: string | null; allocatedAmount: number; receiptCount: number; createdAt: string };
type Allocated = { contributionId: string; receiptNumber: string; memberName: string; amount: number; contributionDate: string; paymentReference: string | null };
type StatementEntry = { id: string; scopeUnitId: string; scopeName: string; transactionDate: string; bankReference: string; amount: number; description: string | null; reconciled: boolean };

export default function DepositManagement({ organisationId, authorizationHeader, canDeposit, canReconcile }: {
  organisationId: string; authorizationHeader: string; canDeposit: boolean; canReconcile: boolean;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [receipts, setReceipts] = useState<Receipt[]>([]);
  const [batches, setBatches] = useState<Batch[]>([]);
  const [selectedBatchId, setSelectedBatchId] = useState("");
  const [allocations, setAllocations] = useState<Allocated[]>([]);
  const [entries, setEntries] = useState<StatementEntry[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
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

  async function reloadScope() {
    if (!organisationId || !scopeUnitId) return;
    const [batchRows, receiptRows] = await Promise.all([
      request<Batch[]>(`/api/organisations/${organisationId}/deposit-batches?scopeUnitId=${scopeUnitId}`),
      request<Receipt[]>(`/api/organisations/${organisationId}/unallocated-cash-contributions?scopeUnitId=${scopeUnitId}`)
    ]);
    setBatches(batchRows);
    setReceipts(receiptRows);
    if (canReconcile) {
      setEntries(await request<StatementEntry[]>(`/api/organisations/${organisationId}/bank-statement-entries?scopeUnitId=${scopeUnitId}`));
    } else setEntries([]);
  }

  async function reloadAllocations(batchId: string) {
    if (!batchId) { setAllocations([]); return; }
    setAllocations(await request<Allocated[]>(`/api/deposit-batches/${batchId}/allocations`));
  }

  useEffect(() => {
    setUnits([]); setScopeUnitId(""); setBatches([]); setReceipts([]); setEntries([]);
    if (!organisationId || !canDeposit) return;
    request<Unit[]>(`/api/organisations/${organisationId}/financial-units`)
      .then(rows => {
        const eligible = rows.filter(unit => unit.active && ["NYIKA", "TABHERA", "CELL"].includes(unit.level));
        setUnits(eligible);
        if (eligible.length) setScopeUnitId(eligible[0].id);
      }).catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader, canDeposit]);

  useEffect(() => {
    setSelectedBatchId(""); setAllocations([]);
    if (scopeUnitId) reloadScope().catch((e: Error) => setError(e.message));
  }, [scopeUnitId, organisationId, authorizationHeader]);

  useEffect(() => { reloadAllocations(selectedBatchId).catch((e: Error) => setError(e.message)); }, [selectedBatchId]);

  const selectedBatch = batches.find(batch => batch.id === selectedBatchId);
  const allocatedIds = new Set(allocations.map(item => item.contributionId));
  const availableReceipts = receipts.filter(item => !allocatedIds.has(item.id));

  async function perform(action: () => Promise<void>, message: string) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); await reloadScope(); setNotice(message); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function createBatch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    await perform(async () => {
      const created = await request<Batch>(`/api/organisations/${organisationId}/deposit-batches`, {
        method: "POST", body: JSON.stringify({
          scopeUnitId, depositDate: data.get("depositDate"), bankName: data.get("bankName"),
          bankReference: data.get("bankReference"), expectedAmount: data.get("expectedAmount"), note: data.get("note")
        })
      });
      setSelectedBatchId(created.id); form.reset();
    }, "Draft deposit batch created.");
  }

  async function allocate(receipt: Receipt) {
    if (!selectedBatch) return;
    await perform(async () => {
      await request(`/api/deposit-batches/${selectedBatch.id}/allocations`, {
        method: "POST", body: JSON.stringify({ contributionId: receipt.id, allocatedAmount: receipt.amount })
      });
      await reloadAllocations(selectedBatch.id);
    }, `Receipt ${receipt.receiptNumber} allocated to the draft batch.`);
  }

  async function removeAllocation(item: Allocated) {
    if (!selectedBatch) return;
    await perform(async () => {
      await request(`/api/deposit-batches/${selectedBatch.id}/allocations/${item.contributionId}`, { method: "DELETE" });
      await reloadAllocations(selectedBatch.id);
    }, `Receipt ${item.receiptNumber} removed from the draft batch.`);
  }

  async function submitBatch() {
    if (!selectedBatch) return;
    await perform(async () => {
      const updated = await request<Batch>(`/api/deposit-batches/${selectedBatch.id}/submit`, { method: "POST" });
      setBatches(rows => rows.map(row => row.id === updated.id ? updated : row));
    }, "Deposit batch submitted for bank reconciliation.");
  }

  async function recordEntry(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    await perform(async () => {
      await request(`/api/organisations/${organisationId}/bank-statement-entries`, {
        method: "POST", body: JSON.stringify({
          scopeUnitId, transactionDate: data.get("transactionDate"), bankReference: data.get("bankReference"),
          amount: data.get("amount"), description: data.get("description")
        })
      });
      form.reset();
    }, "Bank statement entry recorded.");
  }

  async function reconcile(batch: Batch, entry: StatementEntry) {
    await perform(async () => {
      await request(`/api/deposit-batches/${batch.id}/reconcile`, {
        method: "POST", body: JSON.stringify({ statementEntryId: entry.id, note: "Matched against recorded bank statement entry." })
      });
      setSelectedBatchId("");
    }, `Deposit ${batch.bankReference} reconciled successfully.`);
  }

  return <section className="panel">
    <div className="panel-divider" />
    <div className="panel-title"><div><span className="panel-index">05</span><h2>Deposit management</h2></div><span className="panel-tag">CASH CONTROL</span></div>
    <p className="panel-description">Group cash receipts into deposit batches, submit the declared amount and reconcile each batch against a recorded bank statement entry. A receipt can only be allocated once.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    <label>Financial scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)} required><option value="">Select assigned scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
    {canDeposit && <>
      <div className="panel-divider" />
      <div className="panel-title"><div><span className="panel-index">01</span><h3>Create deposit batch</h3></div></div>
      <form className="form-stack" onSubmit={createBatch}>
        <div className="field-pair"><label>Deposit date<input name="depositDate" type="date" defaultValue={new Date().toISOString().slice(0,10)} required /></label><label>Bank name<input name="bankName" maxLength={120} required placeholder="Bank / institution" /></label></div>
        <div className="field-pair"><label>Bank deposit reference<input name="bankReference" maxLength={120} required /></label><label>Expected deposit amount<input name="expectedAmount" type="number" min="0.01" step="0.01" required /></label></div>
        <label>Note<input name="note" maxLength={500} placeholder="Optional deposit note" /></label>
        <button className="primary-button" disabled={busy || !scopeUnitId}>Create draft batch</button>
      </form>
      <div className="panel-divider" />
      <div className="register-heading"><div><h3>Deposit batch register</h3><p>Drafts can be edited; submitted batches are locked.</p></div><span className="count-box">{batches.length} BATCHES</span></div>
      <div className="table-wrap"><table><thead><tr><th>DATE / REFERENCE</th><th>DECLARED</th><th>ALLOCATED</th><th>RECEIPTS</th><th>STATUS</th><th></th></tr></thead><tbody>
        {batches.map(batch => <tr key={batch.id}><td><strong>{batch.bankReference}</strong><small className="table-subtext">{batch.depositDate} · {batch.bankName}</small></td><td>ZAR {Number(batch.expectedAmount).toFixed(2)}</td><td>ZAR {Number(batch.allocatedAmount).toFixed(2)}</td><td>{batch.receiptCount}</td><td>{batch.status}</td><td><button type="button" className="secondary-button" onClick={() => setSelectedBatchId(batch.id)}>Open</button></td></tr>)}
        {!batches.length && <tr><td colSpan={6} className="empty-table">No deposit batches recorded for this scope.</td></tr>}
      </tbody></table></div>
      {selectedBatch && <div className="panel-divider">
        <div className="register-heading"><div><h3>Batch {selectedBatch.bankReference}</h3><p>{selectedBatch.status} · {selectedBatch.receiptCount} allocated receipts</p></div><strong>ZAR {Number(selectedBatch.allocatedAmount).toFixed(2)} / {Number(selectedBatch.expectedAmount).toFixed(2)}</strong></div>
        <div className="table-wrap"><table><thead><tr><th>RECEIPT</th><th>MEMBER</th><th>DATE</th><th>AMOUNT</th><th></th></tr></thead><tbody>
          {allocations.map(item => <tr key={item.contributionId}><td>{item.receiptNumber}</td><td>{item.memberName}</td><td>{item.contributionDate}</td><td>ZAR {Number(item.amount).toFixed(2)}</td><td>{selectedBatch.status === "DRAFT" && <button type="button" className="secondary-button" disabled={busy} onClick={() => removeAllocation(item)}>Remove</button>}</td></tr>)}
          {!allocations.length && <tr><td colSpan={5} className="empty-table">No receipts allocated yet.</td></tr>}
        </tbody></table></div>
        {selectedBatch.status === "DRAFT" && <>
          <h4>Available cash receipts</h4>
          <div className="table-wrap"><table><thead><tr><th>RECEIPT</th><th>MEMBER / CATEGORY</th><th>DATE</th><th>AMOUNT</th><th></th></tr></thead><tbody>
            {availableReceipts.map(item => <tr key={item.id}><td><strong>{item.receiptNumber}</strong></td><td>{item.memberName}<small className="table-subtext">{item.categoryName}</small></td><td>{item.contributionDate}</td><td>ZAR {Number(item.amount).toFixed(2)}</td><td><button type="button" className="secondary-button" disabled={busy} onClick={() => allocate(item)}>Allocate</button></td></tr>)}
            {!availableReceipts.length && <tr><td colSpan={5} className="empty-table">No unallocated cash receipts in this scope.</td></tr>}
          </tbody></table></div>
          <button type="button" className="primary-button" disabled={busy || !allocations.length || Number(selectedBatch.allocatedAmount) !== Number(selectedBatch.expectedAmount)} onClick={submitBatch}>Submit batch for reconciliation</button>
        </>}
      </>}
    </>}
    {canReconcile && <>
      <div className="panel-divider" />
      <div className="panel-title"><div><span className="panel-index">02</span><h3>Bank statement entries</h3></div><span className="panel-tag">CENTRAL RECONCILIATION</span></div>
      <p className="panel-description">Record a deposit transaction from the bank statement. Reconciliation requires the same reference and amount, with a transaction date within three days of the deposit date.</p>
      <form className="form-stack" onSubmit={recordEntry}>
        <div className="field-pair"><label>Statement date<input name="transactionDate" type="date" required /></label><label>Bank reference<input name="bankReference" maxLength={120} required /></label></div>
        <div className="field-pair"><label>Statement amount<input name="amount" type="number" min="0.01" step="0.01" required /></label><label>Description<input name="description" maxLength={500} /></label></div>
        <button className="secondary-button" disabled={busy || !scopeUnitId}>Record statement entry</button>
      </form>
      <div className="table-wrap"><table><thead><tr><th>DATE / REFERENCE</th><th>AMOUNT</th><th>DESCRIPTION</th><th>STATUS</th></tr></thead><tbody>
        {entries.map(entry => <tr key={entry.id}><td><strong>{entry.bankReference}</strong><small className="table-subtext">{entry.transactionDate}</small></td><td>ZAR {Number(entry.amount).toFixed(2)}</td><td>{entry.description || "—"}</td><td>{entry.reconciled ? "RECONCILED" : "UNMATCHED"}</td></tr>)}
        {!entries.length && <tr><td colSpan={4} className="empty-table">No bank statement entries recorded for this scope.</td></tr>}
      </tbody></table></div>
      <div className="panel-divider" />
      <div className="register-heading"><div><h3>Submitted deposits awaiting reconciliation</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>DEPOSIT</th><th>AMOUNT</th><th>DATE</th><th>MATCHING ENTRY</th><th></th></tr></thead><tbody>
        {batches.filter(batch => batch.status === "SUBMITTED").map(batch => {
          const candidate = entries.find(entry => !entry.reconciled && entry.bankReference.toLowerCase() === batch.bankReference.toLowerCase() && Number(entry.amount) === Number(batch.expectedAmount) && Math.abs(new Date(entry.transactionDate).getTime() - new Date(batch.depositDate).getTime()) <= 3 * 86400000);
          return <tr key={batch.id}><td><strong>{batch.bankReference}</strong></td><td>ZAR {Number(batch.expectedAmount).toFixed(2)}</td><td>{batch.depositDate}</td><td>{candidate ? candidate.transactionDate : "No exact match"}</td><td>{candidate && <button type="button" className="secondary-button" disabled={busy} onClick={() => reconcile(batch, candidate)}>Reconcile</button>}</td></tr>;
        })}
        {!batches.some(batch => batch.status === "SUBMITTED") && <tr><td colSpan={5} className="empty-table">No submitted deposits awaiting reconciliation.</td></tr>}
      </tbody></table></div>
    </>}
  </section>;
}
