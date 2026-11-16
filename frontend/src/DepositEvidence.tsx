import { useEffect, useState } from "react";
import DepositScanner from "./DepositScanner";

type Evidence = {
  id: string;
  filename: string;
  contentType: string;
  fileSize: number;
  sha256: string;
  uploadedAt: string;
};

export default function DepositEvidence({ batchId, status, authorizationHeader }: {
  batchId: string;
  status: string;
  authorizationHeader: string;
}) {
  const [items, setItems] = useState<Evidence[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const canUpload = ["DRAFT", "SUBMITTED", "PARTIALLY_RECONCILED"].includes(status);

  async function load() {
    const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/evidence`, {
      headers: { Authorization: authorizationHeader }
    });
    if (!response.ok) throw new Error(`Could not load deposit evidence (${response.status}).`);
    setItems(await response.json() as Evidence[]);
  }

  useEffect(() => {
    setItems([]);
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
    {canUpload && <div className="form-stack">
      <label>Capture or upload deposit slip
        <input type="file" accept="image/jpeg,image/png,image/webp,application/pdf" multiple capture="environment"
          disabled={busy} onChange={event => upload(event.currentTarget.files, event.currentTarget)} />
      </label>
      <small className="table-subtext">JPEG, PNG, WebP or PDF · up to 10 MB per file · up to 10 files per upload. Use the camera option on a mobile device or select existing scans. Multiple images can represent a multi-page deposit package.</small>
      {busy && <p role="status">Uploading evidence…</p>}
    </div>}
    <div className="table-wrap"><table><thead><tr><th>FILE</th><th>FORMAT</th><th>SIZE</th><th>UPLOADED</th><th></th></tr></thead><tbody>
      {items.map(item => <tr key={item.id}>
        <td><strong>{item.filename}</strong><small className="table-subtext">SHA-256 {item.sha256.slice(0, 16)}…</small></td>
        <td>{item.contentType}</td>
        <td>{(item.fileSize / 1024 / 1024).toFixed(2)} MB</td>
        <td>{new Date(item.uploadedAt).toLocaleString()}</td>
        <td><button type="button" className="secondary-button" disabled={busy} onClick={() => openEvidence(item)}>Download</button></td>
      </tr>)}
      {!items.length && <tr><td colSpan={5} className="empty-table">No evidence attached to this deposit.</td></tr>}
    </tbody></table></div>
    <small className="table-subtext">Evidence is access-controlled and retained as submitted. A scan or photograph is supporting documentation, not independent proof of bank settlement or authenticity.</small>
    <DepositScanner batchId={batchId} authorizationHeader={authorizationHeader} evidence={items} />
  </section>;
}
