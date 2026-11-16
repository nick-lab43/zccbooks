import { useEffect, useMemo, useState } from "react";

type ReviewItem = {
  id: string; scopeUnitId: string; scopeName: string; depositDate: string; bankName: string;
  bankReference: string; expectedAmount: number; depositStatus: string; reviewStatus: string;
  note: string | null; responseNote: string | null; submittedAt: string | null;
  receiptCount: number; allocatedAmount: number; submittedBy: string;
};
type History = { action: string; previousStatus: string | null; newStatus: string; note: string | null; changedAt: string; changedBy: string };
type ReviewDetails = {
  allocations: { contributionId: string; receiptNumber: string; zccAppNumber: string; memberName: string; categoryCode: string; categoryName: string; streamCode: string; streamName: string; contributionAmount: number; currency: string; contributionDate: string; paymentMethod: string; paymentReference: string | null; contributionUnit: string; allocatedAmount: number; capturedAt: string; capturedBy: string }[];
  evidence: { id: string; filename: string; contentType: string; fileSize: number; sha256: string; uploadedAt: string; uploadedBy: string }[];
  documents: { id: string; filename: string; fileSize: number; sha256: string; pageCount: number; createdAt: string; createdBy: string }[];
};

export default function DepositReview({ organisationId, authorizationHeader }: {
  organisationId: string; authorizationHeader: string;
}) {
  const [items, setItems] = useState<ReviewItem[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [history, setHistory] = useState<History[]>([]);
  const [details, setDetails] = useState<ReviewDetails | null>(null);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [note, setNote] = useState("");
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

  async function reload() {
    if (!organisationId) return;
    const rows = await request<ReviewItem[]>(`/api/organisations/${organisationId}/deposit-review-queue`);
    setItems(rows);
    if (selectedId && !rows.some(row => row.id === selectedId)) {
      setSelectedId("");
      setHistory([]);
    }
  }

  useEffect(() => {
    setItems([]); setSelectedId(""); setHistory([]); setDetails(null);
    if (organisationId) reload().catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  useEffect(() => {
    if (!selectedId) { setHistory([]); setDetails(null); return; }
    setLoadingDetails(true); setError("");
    Promise.all([
      request<History[]>(`/api/deposit-batches/${selectedId}/review-history`),
      request<ReviewDetails>(`/api/deposit-batches/${selectedId}/review-details`)
    ]).then(([events, detail]) => { setHistory(events); setDetails(detail); })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoadingDetails(false));
  }, [selectedId, authorizationHeader]);

  const selected = items.find(item => item.id === selectedId);

  async function downloadFile(path: string, filename: string) {
    setError("");
    try {
      const response = await fetch(`${apiBase}${path}`, { headers });
      if (!response.ok) throw new Error(`Could not retrieve ${filename} (${response.status}).`);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url; link.download = filename; link.rel = "noopener";
      document.body.appendChild(link); link.click(); link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (e) { setError(e instanceof Error ? e.message : "File download failed."); }
  }

  async function decide(decision: "ACCEPT" | "QUERY" | "REJECT") {
    if (!selected) return;
    if (decision !== "ACCEPT" && !note.trim()) {
      setError("A reason is required when querying or rejecting a package.");
      return;
    }
    setBusy(true); setError(""); setNotice("");
    try {
      await request<ReviewItem>(`/api/deposit-batches/${selected.id}/review`, {
        method: "POST", body: JSON.stringify({ decision, note: note.trim() || null })
      });
      setNote("");
      await reload();
      setNotice(decision === "ACCEPT" ? "Deposit package accepted." : decision === "QUERY" ? "Clarification requested from the submitting administrator." : "Deposit package rejected with an auditable reason.");
    } catch (e) { setError(e instanceof Error ? e.message : "Review decision failed."); }
    finally { setBusy(false); }
  }

  return <section className="panel">
    <div className="panel-title"><div><span className="panel-index">01</span><h2>Central deposit package review</h2></div><span className="panel-tag">FINANCIAL OVERSIGHT</span></div>
    <p className="panel-description">Review submitted deposit packages independently of bank reconciliation. Accept a package, request clarification or reject it with a recorded reason. Every decision is retained in immutable review history.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    <div className="register-heading"><div><h3>Review queue</h3><p>Pending decisions, queried packages and rejected packages awaiting a response.</p></div><span className="count-box">{items.length} PACKAGES</span></div>
    <div className="table-wrap"><table><thead><tr><th>SUBMITTED</th><th>NYIKA / UNIT</th><th>REFERENCE</th><th>DECLARED</th><th>RECEIPTS</th><th>STATUS</th><th></th></tr></thead><tbody>
      {items.map(item => <tr key={item.id}><td>{item.submittedAt ? new Date(item.submittedAt).toLocaleString() : "—"}<small className="table-subtext">{item.submittedBy}</small></td><td>{item.scopeName}</td><td><strong>{item.bankReference}</strong><small className="table-subtext">{item.depositDate} · {item.bankName}</small></td><td>ZAR {Number(item.expectedAmount).toFixed(2)}</td><td>{item.receiptCount}</td><td>{item.reviewStatus}</td><td><button className="secondary-button" onClick={() => { setSelectedId(item.id); setNote(""); }}>Review</button></td></tr>)}
      {!items.length && <tr><td colSpan={7} className="empty-table">No deposit packages are awaiting central review.</td></tr>}
    </tbody></table></div>
    {selected && <div className="panel-divider">
      <div className="register-heading"><div><h3>Package {selected.bankReference}</h3><p>{selected.scopeName} · submitted by {selected.submittedBy}</p></div><strong>ZAR {Number(selected.expectedAmount).toFixed(2)}<small className="table-subtext">{selected.receiptCount} receipts · {selected.reviewStatus}</small></strong></div>
      {loadingDetails && <p role="status">Loading evidence and allocation details…</p>}
      {details && <>
        <div className="panel-divider">
          <div className="register-heading"><div><h3>Receipt allocation breakdown</h3><p>Every receipt linked to this deposit, with the member, contribution category and financial stream.</p></div><span className="count-box">{details.allocations.length} RECEIPTS</span></div>
          <div className="table-wrap"><table><thead><tr><th>RECEIPT / MEMBER</th><th>CATEGORY</th><th>STREAM</th><th>DATE / UNIT</th><th>CONTRIBUTION</th><th>ALLOCATED</th></tr></thead><tbody>
            {details.allocations.map(line => <tr key={line.contributionId}>
              <td><strong>{line.receiptNumber}</strong><small className="table-subtext">{line.memberName} · {line.zccAppNumber}</small></td>
              <td>{line.categoryName}<small className="table-subtext">{line.categoryCode}</small></td>
              <td>{line.streamName}<small className="table-subtext">{line.streamCode}</small></td>
              <td>{line.contributionDate}<small className="table-subtext">{line.contributionUnit}</small></td>
              <td>{line.currency} {Number(line.contributionAmount).toFixed(2)}</td>
              <td>{line.currency} {Number(line.allocatedAmount).toFixed(2)}</td>
            </tr>)}
            {!details.allocations.length && <tr><td colSpan={6} className="empty-table">No receipt allocations found.</td></tr>}
          </tbody></table></div>
          <div className="register-heading"><div><h3>Original deposit evidence</h3><p>Original uploaded photographs and documents, with uploader and recorded digest.</p></div><span className="count-box">{details.evidence.length} FILES</span></div>
          <div className="table-wrap"><table><thead><tr><th>FILE</th><th>FORMAT / SIZE</th><th>UPLOADED BY</th><th>UPLOADED</th><th></th></tr></thead><tbody>
            {details.evidence.map(file => <tr key={file.id}>
              <td><strong>{file.filename}</strong><small className="table-subtext">SHA-256 {file.sha256}</small></td>
              <td>{file.contentType}<small className="table-subtext">{(file.fileSize / 1048576).toFixed(2)} MB</small></td>
              <td>{file.uploadedBy}</td><td>{new Date(file.uploadedAt).toLocaleString()}</td>
              <td><button className="secondary-button" onClick={() => downloadFile(`/api/deposit-batches/${selected.id}/evidence/${file.id}/content`, file.filename)}>Download original</button></td>
            </tr>)}
            {!details.evidence.length && <tr><td colSpan={5} className="empty-table">No original evidence attached.</td></tr>}
          </tbody></table></div>
          <div className="register-heading"><div><h3>Processed scan documents</h3><p>PDF derivatives created from the original pages. Originals remain unchanged.</p></div><span className="count-box">{details.documents.length} PDFS</span></div>
          <div className="table-wrap"><table><thead><tr><th>DOCUMENT</th><th>PAGES / SIZE</th><th>CREATED BY</th><th>CREATED</th><th></th></tr></thead><tbody>
            {details.documents.map(file => <tr key={file.id}>
              <td><strong>{file.filename}</strong><small className="table-subtext">SHA-256 {file.sha256}</small></td>
              <td>{file.pageCount} pages<small className="table-subtext">{(file.fileSize / 1048576).toFixed(2)} MB</small></td>
              <td>{file.createdBy}</td><td>{new Date(file.createdAt).toLocaleString()}</td>
              <td><button className="secondary-button" onClick={() => downloadFile(`/api/deposit-batches/${selected.id}/documents/${file.id}/content`, file.filename)}>Download PDF</button></td>
            </tr>)}
            {!details.documents.length && <tr><td colSpan={5} className="empty-table">No processed scan documents created.</td></tr>}
          </tbody></table></div>
        </div>
      </>}
      {selected.note && <p className="panel-description">Submitter note: {selected.note}</p>}
      {selected.responseNote && <p className="panel-description">Submitter response: {selected.responseNote}</p>}
      <label className="form-stack">Review note<textarea value={note} onChange={e => setNote(e.target.value)} maxLength={1000} rows={3} placeholder="Required for query or rejection; optional for acceptance." /></label>
      <div className="field-pair" style={{marginTop:12}}>
        <button className="primary-button" disabled={busy || selected.reviewStatus !== "PENDING_REVIEW"} onClick={() => decide("ACCEPT")}>Accept package</button>
        <button className="secondary-button" disabled={busy || selected.reviewStatus !== "PENDING_REVIEW" || !note.trim()} onClick={() => decide("QUERY")}>Request clarification</button>
        <button className="secondary-button" disabled={busy || selected.reviewStatus !== "PENDING_REVIEW" || !note.trim()} onClick={() => decide("REJECT")}>Reject package</button>
      </div>
      <div className="panel-divider" />
      <h3>Immutable review history</h3>
      <div className="table-wrap"><table><thead><tr><th>DATE / TIME</th><th>ACTOR</th><th>ACTION</th><th>STATUS</th><th>NOTE</th></tr></thead><tbody>
        {history.map((event, index) => <tr key={index}><td>{new Date(event.changedAt).toLocaleString()}</td><td>{event.changedBy}</td><td>{event.action}</td><td>{event.previousStatus ? event.previousStatus + " → " : ""}{event.newStatus}</td><td>{event.note || "—"}</td></tr>)}
        {!history.length && <tr><td colSpan={5} className="empty-table">No review history recorded.</td></tr>}
      </tbody></table></div>
    </div>}
  </section>;
}
