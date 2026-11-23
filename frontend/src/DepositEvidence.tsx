import { useEffect, useState } from "react";
import DepositScanner from "./DepositScanner";
import DepositSlipOcr from "./DepositSlipOcr";

type Evidence = {
  id: string;
  filename: string;
  contentType: string;
  fileSize: number;
  sha256: string;
  uploadedAt: string;
};

type IntegrityCheck = {
  id: string;
  runId: string;
  targetType: string;
  targetId: string;
  filename: string;
  storedSha256: string;
  computedSha256: string;
  integrityVerified: boolean;
  checkedBy: string;
  checkedAt: string;
};

type IntegrityCheckRun = {
  runId: string;
  filesChecked: number;
  filesVerified: number;
  integrityFailures: number;
  results: IntegrityCheck[];
};

export default function DepositEvidence({ batchId, status, authorizationHeader }: {
  batchId: string;
  status: string;
  authorizationHeader: string;
}) {
  const [items, setItems] = useState<Evidence[]>([]);
  const [integrityChecks, setIntegrityChecks] = useState<IntegrityCheck[]>([]);
  const [busy, setBusy] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const canUpload = ["DRAFT", "SUBMITTED", "PARTIALLY_RECONCILED"].includes(status);

  async function load() {
    const [evidenceResponse, checksResponse] = await Promise.all([
      fetch(`${apiBase}/api/deposit-batches/${batchId}/evidence`, {
        headers: { Authorization: authorizationHeader }
      }),
      fetch(`${apiBase}/api/deposit-batches/${batchId}/integrity-checks`, {
        headers: { Authorization: authorizationHeader }
      })
    ]);
    if (!evidenceResponse.ok) throw new Error(`Could not load deposit evidence (${evidenceResponse.status}).`);
    if (!checksResponse.ok) throw new Error(`Could not load integrity history (${checksResponse.status}).`);
    setItems(await evidenceResponse.json() as Evidence[]);
    setIntegrityChecks(await checksResponse.json() as IntegrityCheck[]);
  }

  useEffect(() => {
    setItems([]);
    setIntegrityChecks([]);
    setError("");
    load().catch((e: Error) => setError(e.message));
  }, [batchId, authorizationHeader]);

  async function upload(files: FileList | null, input: HTMLInputElement) {
    if (!files?.length) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const form = new FormData();
      Array.from(files).forEach(file => form.append("files", file));
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/evidence`, {
        method: "POST",
        headers: { Authorization: authorizationHeader },
        body: form
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Evidence upload failed (${response.status}).`);
      }
      setItems(await response.json() as Evidence[]);
      setNotice("Original evidence stored securely and linked to this deposit.");
      input.value = "";
    } catch (e) {
      setError(e instanceof Error ? e.message : "Evidence upload failed.");
    } finally {
      setBusy(false);
    }
  }

  async function verifyIntegrity() {
    setVerifying(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/integrity-checks`, {
        method: "POST",
        headers: { Authorization: authorizationHeader }
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Integrity verification failed (${response.status}).`);
      }
      const result = await response.json() as IntegrityCheckRun;
      setIntegrityChecks(current => [...result.results, ...current]);
      setNotice(result.integrityFailures === 0
        ? `Integrity verified: all ${result.filesVerified} stored files match their recorded SHA-256 digests.`
        : `Integrity warning: ${result.integrityFailures} of ${result.filesChecked} files do not match their recorded SHA-256 digests. Preserve the records and investigate.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Integrity verification failed.");
    } finally {
      setVerifying(false);
    }
  }

  async function openEvidence(item: Evidence) {
    setError("");
    try {
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/evidence/${item.id}/content`, {
        headers: { Authorization: authorizationHeader }
      });
      if (!response.ok) throw new Error(`Could not retrieve evidence (${response.status}).`);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = item.filename;
      anchor.rel = "noopener";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not retrieve evidence.");
    }
  }

  return <section className="panel-divider">
    <div className="register-heading">
      <div><h3>Deposit evidence</h3><p>Original photographs and documents remain unchanged after upload.</p></div>
      <span className="count-box">{items.length} FILES</span>
    </div>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    {canUpload && <div className="deposit-evidence-actions">
      <div className="deposit-evidence-action">
        <strong>Take a deposit slip photo</strong>
        <p>On supported mobile devices, open the rear camera and photograph the original slip.</p>
        <label className="primary-button deposit-evidence-input">Open camera
          <input aria-label="Take a deposit slip photo" type="file" accept="image/*" capture="environment"
            disabled={busy || verifying} onChange={event => upload(event.currentTarget.files, event.currentTarget)} />
        </label>
      </div>
      <div className="deposit-evidence-action">
        <strong>Upload an existing image or PDF</strong>
        <p>Select a saved photograph, scan or bank document from this device.</p>
        <label className="secondary-button deposit-evidence-input">Choose files
          <input aria-label="Upload deposit slip files" type="file" accept="image/jpeg,image/png,image/webp,application/pdf" multiple
            disabled={busy || verifying} onChange={event => upload(event.currentTarget.files, event.currentTarget)} />
        </label>
      </div>
      <small className="table-subtext">JPEG, PNG, WebP or PDF · up to 10 MB per file · up to 10 files per upload. Evidence is attached to this batch and retained in its original form.</small>
      {busy && <p role="status">Uploading evidence…</p>}
    </div>}
    <div className="table-wrap"><table><thead><tr><th>FILE</th><th>FORMAT</th><th>SIZE</th><th>UPLOADED</th><th></th></tr></thead><tbody>
      {items.map(item => <tr key={item.id}>
        <td><strong>{item.filename}</strong><small className="table-subtext">SHA-256 {item.sha256.slice(0, 16)}…</small></td>
        <td>{item.contentType}</td>
        <td>{(item.fileSize / 1024 / 1024).toFixed(2)} MB</td>
        <td>{new Date(item.uploadedAt).toLocaleString()}</td>
        <td><button type="button" className="secondary-button" disabled={busy || verifying} onClick={() => openEvidence(item)}>Download</button></td>
      </tr>)}
      {!items.length && <tr><td colSpan={5} className="empty-table">No evidence attached to this deposit.</td></tr>}
    </tbody></table></div>
    <div className="panel-divider">
      <div className="register-heading">
        <div><h4>File integrity verification</h4><p>Recalculate SHA-256 from the bytes currently stored and compare it with the digest recorded at upload or document creation.</p></div>
        <button type="button" className="secondary-button" disabled={verifying || busy || items.length === 0}
          onClick={verifyIntegrity}>{verifying ? "Verifying…" : "Verify stored files"}</button>
      </div>
      <div className="table-wrap"><table><thead><tr><th>FILE</th><th>TYPE</th><th>RESULT</th><th>CHECKED BY</th><th>CHECKED AT</th></tr></thead><tbody>
        {integrityChecks.map(check => <tr key={check.id}>
          <td><strong>{check.filename}</strong><small className="table-subtext">Run {check.runId.slice(0, 8)}</small></td>
          <td>{check.targetType === "EVIDENCE" ? "Original evidence" : "Scanned PDF"}</td>
          <td><strong>{check.integrityVerified ? "MATCH" : "MISMATCH"}</strong></td>
          <td>{check.checkedBy}</td>
          <td>{new Date(check.checkedAt).toLocaleString()}</td>
        </tr>)}
        {!integrityChecks.length && <tr><td colSpan={5} className="empty-table">No integrity checks have been run.</td></tr>}
      </tbody></table></div>
      <small className="table-subtext">A match confirms only that the current stored bytes match the digest recorded by this application. It does not prove who created the file, when the source photograph was taken, or whether the document was authentic before upload.</small>
    </div>
    <small className="table-subtext">Evidence is access-controlled and retained as submitted. A scan or photograph is supporting documentation, not independent proof of bank settlement or authenticity.</small>
    <DepositScanner batchId={batchId} authorizationHeader={authorizationHeader} evidence={items} />
    <DepositSlipOcr batchId={batchId} authorizationHeader={authorizationHeader} evidence={items} />
  </section>;
}
