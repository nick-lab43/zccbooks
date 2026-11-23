import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import DepositEvidence from "./DepositEvidence";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Receipt = { id: string; receiptNumber: string; memberName: string; amount: number; contributionDate: string; paymentMethod: string; paymentReference: string | null; categoryName: string; scopeUnitId: string };
type Batch = { id: string; scopeUnitId: string; scopeName: string; depositDate: string; bankName: string; bankReference: string; expectedAmount: number; status: string; note: string | null; allocatedAmount: number; receiptCount: number; bankedAmount: number; outstandingAmount: number; outstandingNote: string | null; createdAt: string; reviewStatus: string; reviewResponseNote: string | null };
type Allocated = { contributionId: string; receiptNumber: string; memberName: string; amount: number; contributionDate: string; paymentReference: string | null };
type StatementEntry = { id: string; scopeUnitId: string; scopeName: string; transactionDate: string; bankReference: string; amount: number; entryType: string; description: string | null; reconciled: boolean };
type StatementImportResult = { importId: string; fileName: string; importedRows: number; duplicateRows: number };

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
  const [partialNotes, setPartialNotes] = useState<Record<string, string>>({});
  const [reviewResponse, setReviewResponse] = useState("");
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
  const unallocatedTotal = receipts.reduce((total, receipt) => total + Number(receipt.amount), 0);
  const draftBatchCount = batches.filter(batch => batch.status === "DRAFT").length;
  const awaitingReviewCount = batches.filter(batch => ["SUBMITTED", "PARTIALLY_RECONCILED"].includes(batch.status) && !["ACCEPTED", "RECONCILED"].includes(batch.reviewStatus)).length;
  const reviewedCount = batches.filter(batch => ["ACCEPTED", "RECONCILED"].includes(batch.reviewStatus) || batch.status === "RECONCILED").length;

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
    }, "Deposit batch submitted to Central Financial Administration for review.");
  }

  async function respondToReview() {
    if (!selectedBatch || !reviewResponse.trim()) return;
    await perform(async () => {
      await request(`/api/deposit-batches/${selectedBatch.id}/review-response`, {
        method: "POST", body: JSON.stringify({ note: reviewResponse.trim() })
      });
      setReviewResponse("");
      setSelectedBatchId(selectedBatch.id);
    }, "Response submitted to Central Financial Administration for another review.");
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

  async function importStatement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const file = (new FormData(form).get("statementFile")) as File | null;
    if (!file || file.size === 0) { setError("Choose a CSV statement file."); return; }
    if (file.size > 5 * 1024 * 1024) { setError("The statement file must be no larger than 5 MB."); return; }
    if (!file.name.toLowerCase().endsWith(".csv")) { setError("Only CSV statement files are supported."); return; }

    setBusy(true); setError(""); setNotice("");
    try {
      const payload = new FormData();
      payload.append("file", file);
      const response = await fetch(
        `${apiBase}/api/organisations/${organisationId}/bank-statement-imports?scopeUnitId=${scopeUnitId}`,
        { method: "POST", headers, body: payload }
      );
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Statement import failed (${response.status}).`);
      }
      const result = await response.json() as StatementImportResult;
      form.reset();
      await reloadScope();
      setNotice(`Imported ${result.importedRows} bank statement rows; skipped ${result.duplicateRows} duplicates. File: ${result.fileName}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The statement could not be imported.");
    } finally {
      setBusy(false);
    }
  }

  async function reconcile(batch: Batch, entry: StatementEntry, note: string) {
    await perform(async () => {
      await request(`/api/deposit-batches/${batch.id}/reconcile`, {
        method: "POST", body: JSON.stringify({ statementEntryId: entry.id, note })
      });
      setPartialNotes(rows => { const next = { ...rows }; delete next[batch.id]; return next; });
      setSelectedBatchId("");
    }, `Bank credit of ZAR ${Number(entry.amount).toFixed(2)} applied to deposit ${batch.bankReference}.`);
  }

  return <section className="panel">
    <div className="panel-divider" />
    <div className="panel-title"><div><span className="panel-index">05</span><h2>Deposit management</h2></div><span className="panel-tag">CASH CONTROL</span></div>
    <p className="panel-description">Group cash receipts into deposit batches, then reconcile the declared amount against one or more bank credits. Partial deposits retain an outstanding balance and require an explanation. A receipt can only be allocated once.</p>
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
        {batches.map(batch => <tr key={batch.id}><td><strong>{batch.bankReference}</strong><small className="table-subtext">{batch.depositDate} · {batch.bankName}</small></td><td>ZAR {Number(batch.expectedAmount).toFixed(2)}</td><td>ZAR {Number(batch.allocatedAmount).toFixed(2)}</td><td>{batch.receiptCount}</td><td><strong>{batch.status.replace(/_/g, " ")}</strong><small className="table-subtext">{batch.reviewStatus.replace(/_/g, " ")}</small></td><td><button type="button" className="secondary-button" onClick={() => setSelectedBatchId(batch.id)}>Open</button></td></tr>)}
        {!batches.length && <tr><td colSpan={6} className="empty-table">No deposit batches recorded for this scope.</td></tr>}
      </tbody></table></div>
      {selectedBatch && <div className="panel-divider">
        <DepositEvidence batchId={selectedBatch.id} status={selectedBatch.status} authorizationHeader={authorizationHeader} />
        <div className="register-heading"><div><h3>Batch {selectedBatch.bankReference}</h3><p>{selectedBatch.status} · {selectedBatch.reviewStatus} · {selectedBatch.receiptCount} allocated receipts</p></div><strong>Allocated ZAR {Number(selectedBatch.allocatedAmount).toFixed(2)}<small className="table-subtext">Banked ZAR {Number(selectedBatch.bankedAmount).toFixed(2)} · Outstanding ZAR {Number(selectedBatch.outstandingAmount).toFixed(2)}</small></strong></div>
        <div className="table-wrap"><table><thead><tr><th>RECEIPT</th><th>MEMBER</th><th>DATE</th><th>AMOUNT</th><th></th></tr></thead><tbody>
          {allocations.map(item => <tr key={item.contributionId}><td>{item.receiptNumber}</td><td>{item.memberName}</td><td>{item.contributionDate}</td><td>ZAR {Number(item.amount).toFixed(2)}</td><td>{selectedBatch.status === "DRAFT" && <button type="button" className="secondary-button" disabled={busy} onClick={() => removeAllocation(item)}>Remove</button>}</td></tr>)}
          {!allocations.length && <tr><td colSpan={5} className="empty-table">No receipts allocated yet.</td></tr>}
        </tbody></table></div>
        {(selectedBatch.reviewStatus === "QUERIED" || selectedBatch.reviewStatus === "REJECTED") && canDeposit && <div className="panel-divider">
          <h4>Central review response required</h4>
          {selectedBatch.reviewResponseNote && <p className="panel-description">Previous response: {selectedBatch.reviewResponseNote}</p>}
          <label className="form-stack">Response to reviewer<textarea value={reviewResponse} onChange={e => setReviewResponse(e.target.value)} rows={3} maxLength={1000} placeholder="Explain the clarification or corrective action taken." /></label>
          <button type="button" className="primary-button" disabled={busy || !reviewResponse.trim()} onClick={respondToReview}>Submit response for re-review</button>
        </div>}
        {selectedBatch.status === "DRAFT" && <>
          <h4>Available cash receipts</h4>
          <div className="table-wrap"><table><thead><tr><th>RECEIPT</th><th>MEMBER / CATEGORY</th><th>DATE</th><th>AMOUNT</th><th></th></tr></thead><tbody>
            {availableReceipts.map(item => <tr key={item.id}><td><strong>{item.receiptNumber}</strong></td><td>{item.memberName}<small className="table-subtext">{item.categoryName}</small></td><td>{item.contributionDate}</td><td>ZAR {Number(item.amount).toFixed(2)}</td><td><button type="button" className="secondary-button" disabled={busy} onClick={() => allocate(item)}>Allocate</button></td></tr>)}
            {!availableReceipts.length && <tr><td colSpan={5} className="empty-table">No unallocated cash receipts in this scope.</td></tr>}
          </tbody></table></div>
          <button type="button" className="primary-button" disabled={busy || !allocations.length || Number(selectedBatch.allocatedAmount) !== Number(selectedBatch.expectedAmount)} onClick={submitBatch}>Submit to Central Finance for review</button>
        </>}
      </div>}
    </>}
    {canReconcile && <>
      <div className="panel-divider" />
      <div className="panel-title"><div><span className="panel-index">02</span><h3>Bank statement entries</h3></div><span className="panel-tag">CENTRAL RECONCILIATION</span></div>
      <p className="panel-description">Import a bank CSV or record individual statement entries. Imports accept ISO dates, references and non-zero amounts; positive values are credits and negative values are debits. Files are limited to 5 MB and 10,000 rows. Exact duplicate files are rejected and duplicate transactions are skipped.</p>
      <form className="form-stack" onSubmit={importStatement}>
        <label>Bank statement CSV<input name="statementFile" type="file" accept=".csv,text/csv" required disabled={busy || !scopeUnitId} /></label>
        <small className="table-subtext">Required headers: date, reference, amount. Optional headers: description, type. Dates must use YYYY-MM-DD.</small>
        <button className="primary-button" disabled={busy || !scopeUnitId}>Import statement CSV</button>
      </form>
      <div className="panel-divider" />
      <h4>Record a single statement entry</h4>
      <form className="form-stack" onSubmit={recordEntry}>
        <div className="field-pair"><label>Statement date<input name="transactionDate" type="date" required /></label><label>Bank reference<input name="bankReference" maxLength={120} required /></label></div>
        <div className="field-pair"><label>Statement amount<input name="amount" type="number" min="0.01" step="0.01" required /></label><label>Description<input name="description" maxLength={500} /></label></div>
        <button className="secondary-button" disabled={busy || !scopeUnitId}>Record statement entry</button>
      </form>
      <div className="table-wrap"><table><thead><tr><th>DATE / REFERENCE</th><th>AMOUNT</th><th>DESCRIPTION</th><th>STATUS</th></tr></thead><tbody>
        {entries.map(entry => <tr key={entry.id}><td><strong>{entry.bankReference}</strong><small className="table-subtext">{entry.transactionDate}</small></td><td>ZAR {Number(entry.amount).toFixed(2)}</td><td>{entry.description || "—"}</td><td>{entry.entryType} · {entry.reconciled ? "RECONCILED" : "UNMATCHED"}</td></tr>)}
        {!entries.length && <tr><td colSpan={4} className="empty-table">No bank statement entries recorded for this scope.</td></tr>}
      </tbody></table></div>
      <div className="panel-divider" />
      <div className="register-heading"><div><h3>Submitted deposits awaiting reconciliation</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>DEPOSIT</th><th>ALLOCATED</th><th>BANKED</th><th>OUTSTANDING</th><th>DATE</th><th>MATCHING CREDIT</th><th>EXPLANATION / ACTION</th></tr></thead><tbody>
        {batches.filter(batch => ["SUBMITTED", "PARTIALLY_RECONCILED"].includes(batch.status)).map(batch => {
          const candidates = entries.filter(entry => !entry.reconciled && entry.entryType === "CREDIT" && entry.bankReference.toLowerCase() === batch.bankReference.toLowerCase() && Number(entry.amount) > 0 && Number(entry.amount) <= Number(batch.outstandingAmount) && Math.abs(new Date(entry.transactionDate).getTime() - new Date(batch.depositDate).getTime()) <= 3 * 86400000);
          const note = partialNotes[batch.id] ?? "";
          return <tr key={batch.id}>
            <td><strong>{batch.bankReference}</strong><small className="table-subtext">{batch.status}</small></td>
            <td>ZAR {Number(batch.allocatedAmount).toFixed(2)}</td>
            <td>ZAR {Number(batch.bankedAmount).toFixed(2)}</td>
            <td>ZAR {Number(batch.outstandingAmount).toFixed(2)}</td>
            <td>{batch.depositDate}</td>
            <td>{candidates.length ? <div className="form-stack">{candidates.map(candidate => <div key={candidate.id}><strong>ZAR {Number(candidate.amount).toFixed(2)}</strong><small className="table-subtext">{candidate.transactionDate} · {candidate.description || candidate.bankReference}</small></div>)}</div> : "No eligible credit"}</td>
            <td>{candidates.length > 0 && <div className="form-stack"><input aria-label={`Outstanding balance explanation for ${batch.bankReference}`} value={note} maxLength={500} placeholder="Required if partial" onChange={event => setPartialNotes(rows => ({ ...rows, [batch.id]: event.target.value }))} />{candidates.map(candidate => {
              const needsExplanation = Number(candidate.amount) < Number(batch.outstandingAmount);
              return <button key={candidate.id} type="button" className="secondary-button" disabled={busy || (needsExplanation && !note.trim())} onClick={() => reconcile(batch, candidate, note)}>Apply ZAR {Number(candidate.amount).toFixed(2)}</button>;
            })}</div>}</td>
          </tr>;
        })}
        {!batches.some(batch => ["SUBMITTED", "PARTIALLY_RECONCILED"].includes(batch.status)) && <tr><td colSpan={7} className="empty-table">No submitted deposits awaiting reconciliation.</td></tr>}
      </tbody></table></div>
    </>}
  </section>;
}
