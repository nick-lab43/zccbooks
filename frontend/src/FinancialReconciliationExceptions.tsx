import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; level: string; code: string; name: string; active: boolean };
type ExceptionRow = {
  id: string; sourceType: string; sourceId: string; exceptionType: string; status: string;
  summary: string; resolutionNote: string | null; createdAt: string; updatedAt: string; scopeName: string;
};

export default function FinancialReconciliationExceptions({ organisationId, authorizationHeader, canImport, canManage }: {
  organisationId: string; authorizationHeader: string; canImport: boolean; canManage: boolean;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [rows, setRows] = useState<ExceptionRow[]>([]);
  const [includeResolved, setIncludeResolved] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [resolutionNotes, setResolutionNotes] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const isForm = options?.body instanceof FormData;
    const response = await fetch(`${apiBase}${path}`, {
      ...options,
      headers: { ...(isForm ? {} : { "Content-Type": "application/json" }), ...headers, ...options?.headers }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.status === 204 ? undefined as T : response.json() as Promise<T>;
  }

  async function reload() {
    if (!organisationId || !scopeUnitId) return;
    setRows(await request<ExceptionRow[]>(
      `/api/organisations/${organisationId}/reconciliation-exceptions?scopeUnitId=${scopeUnitId}&includeResolved=${includeResolved}`
    ));
  }

  useEffect(() => {
    setUnits([]); setScopeUnitId(""); setRows([]);
    if (!organisationId) return;
    request<Unit[]>(`/api/organisations/${organisationId}/financial-units`)
      .then(result => {
        const eligible = result.filter(unit => unit.active && ["NYIKA", "TABHERA", "CELL"].includes(unit.level));
        setUnits(eligible);
        if (eligible.length) setScopeUnitId(eligible[0].id);
      }).catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  useEffect(() => {
    if (scopeUnitId) reload().catch((e: Error) => setError(e.message));
  }, [scopeUnitId, includeResolved, organisationId, authorizationHeader]);

  async function perform(action: () => Promise<void>, message: string) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); await reload(); setNotice(message); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function importFile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file || !scopeUnitId) return;
    const form = event.currentTarget;
    let importSummary = "";
    await perform(async () => {
      const data = new FormData();
      data.append("file", file);
      const result = await request<{ fileName: string; importedRows: number; duplicateRows: number }>(
        `/api/organisations/${organisationId}/bank-statement-imports?scopeUnitId=${scopeUnitId}`,
        { method: "POST", body: data }
      );
      importSummary = `Imported ${result.importedRows} transactions from ${result.fileName}; skipped ${result.duplicateRows} duplicate rows.`;
      setFile(null);
      form.reset();
    }, "Statement import completed.");
    if (importSummary) setNotice(importSummary);
  }

  async function scan() {
    await perform(async () => {
      const result = await request<{ newExceptions: number }>(
        `/api/organisations/${organisationId}/reconciliation-exceptions/scan?scopeUnitId=${scopeUnitId}`,
        { method: "POST" }
      );
      setNotice(`Exception scan completed. ${result.newExceptions} new exception(s) created.`);
    }, "Exception scan completed.");
  }

  async function updateException(row: ExceptionRow, status: "UNDER_REVIEW" | "RESOLVED") {
    await perform(async () => {
      await request(`/api/reconciliation-exceptions/${row.id}`, {
        method: "PATCH",
        body: JSON.stringify({ status, resolutionNote: resolutionNotes[row.id] ?? "" })
      });
      setResolutionNotes(current => ({ ...current, [row.id]: "" }));
    }, status === "RESOLVED" ? "Exception resolved and audit-recorded." : "Exception moved to review.");
  }

  return <section className="panel">
    <div className="panel-divider" />
    <div className="panel-title"><div><span className="panel-index">06</span><h2>Bank imports &amp; reconciliation exceptions</h2></div><span className="panel-tag">CONTROL &amp; INVESTIGATION</span></div>
    <p className="panel-description">Import bank statement CSV files, identify unmatched credits and deposits, and record a documented resolution for each exception. Imports are scoped and duplicate-protected.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    <label>Financial scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)} required><option value="">Select assigned scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
    {canImport && <>
      <div className="panel-divider" />
      <h3>Import bank statement</h3>
      <p className="panel-description">CSV columns: date, reference, amount, description (optional), type (optional). Dates use YYYY-MM-DD. Negative amounts are treated as debits; positive amounts are credits.</p>
      <form className="form-stack" onSubmit={importFile}>
        <label>Statement CSV<input type="file" accept=".csv,text/csv" onChange={e => setFile(e.target.files?.[0] ?? null)} required /></label>
        <button className="secondary-button" disabled={busy || !file || !scopeUnitId}>Import statement file</button>
      </form>
    </>}
    {canManage && <>
      <div className="panel-divider" />
      <div className="register-heading"><div><h3>Exception detection</h3><p>Checks submitted deposits against imported and manually recorded statement entries.</p></div></div>
      <button type="button" className="primary-button" disabled={busy || !scopeUnitId} onClick={scan}>Scan for exceptions</button>
    </>}
    <div className="panel-divider" />
    <div className="register-heading"><div><h3>Exception register</h3><p>Every resolution requires a note and is written to the administrator audit history.</p></div><label className="checkbox-label"><input type="checkbox" checked={includeResolved} onChange={e => setIncludeResolved(e.target.checked)} />Include resolved</label></div>
    <div className="table-wrap"><table><thead><tr><th>TYPE / SOURCE</th><th>ISSUE</th><th>STATUS</th><th>RESOLUTION NOTE</th><th>ACTION</th></tr></thead><tbody>
      {rows.map(row => <tr key={row.id}>
        <td><strong>{row.exceptionType.replaceAll("_", " ")}</strong><small className="table-subtext">{row.sourceType.replaceAll("_", " ")} · {row.scopeName}</small></td>
        <td>{row.summary}<small className="table-subtext">{new Date(row.createdAt).toLocaleString()}</small></td>
        <td>{row.status}</td>
        <td>{row.status === "RESOLVED" ? row.resolutionNote : <input aria-label="Resolution note" value={resolutionNotes[row.id] ?? ""} onChange={e => setResolutionNotes(current => ({ ...current, [row.id]: e.target.value }))} maxLength={1000} placeholder="Required to resolve" />}</td>
        <td>{canManage && row.status !== "RESOLVED" && <div className="action-stack"><button type="button" className="secondary-button" disabled={busy || row.status === "UNDER_REVIEW"} onClick={() => updateException(row, "UNDER_REVIEW")}>Review</button><button type="button" className="primary-button" disabled={busy || !(resolutionNotes[row.id] ?? "").trim()} onClick={() => updateException(row, "RESOLVED")}>Resolve</button></div>}</td>
      </tr>)}
      {!rows.length && <tr><td colSpan={5} className="empty-table">No reconciliation exceptions in this scope.</td></tr>}
    </tbody></table></div>
  </section>;
}
