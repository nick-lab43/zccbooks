import { useEffect, useMemo, useState } from "react";

type Evidence = {
  id: string; filename: string; contentType: string; fileSize: number;
  sha256: string; uploadedBy: string; uploadedAt: string;
};

export default function ExpenditureEvidence({ expenseId, authorizationHeader, canCapture, locked }: {
  expenseId: string; authorizationHeader: string; canCapture: boolean; locked: boolean;
}) {
  const [items, setItems] = useState<Evidence[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function load() {
    const response = await fetch(`${apiBase}/api/expenditures/${expenseId}/evidence`, { headers });
    if (!response.ok) throw new Error(`Unable to load evidence (${response.status}).`);
    setItems(await response.json() as Evidence[]);
  }

  useEffect(() => {
    let active = true;
    fetch(`${apiBase}/api/expenditures/${expenseId}/evidence`, { headers })
      .then(async response => {
        if (!response.ok) throw new Error(`Unable to load evidence (${response.status}).`);
        return response.json() as Promise<Evidence[]>;
      })
      .then(rows => { if (active) setItems(rows); })
      .catch((e: Error) => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [expenseId, authorizationHeader]);

  async function upload(event: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    if (!files.length) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const form = new FormData();
      files.forEach(file => form.append("files", file));
      const response = await fetch(`${apiBase}/api/expenditures/${expenseId}/evidence`, {
        method: "POST", headers, body: form
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Evidence upload failed (${response.status}).`);
      }
      setItems(await response.json() as Evidence[]);
      setNotice("Evidence attached.");
      event.target.value = "";
    } catch (e) {
      setError(e instanceof Error ? e.message : "Evidence upload failed.");
    } finally {
      setBusy(false);
    }
  }

  async function download(item: Evidence) {
    setError("");
    try {
      const response = await fetch(`${apiBase}/api/expenditures/${expenseId}/evidence/${item.id}/content`, { headers });
      if (!response.ok) throw new Error(`Evidence download failed (${response.status}).`);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = item.filename;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Evidence download failed.");
    }
  }

  return <div className="form-stack">
    <strong>Supporting evidence · {items.length}/10</strong>
    {error && <div className="message error" role="status">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    {items.map(item => <div className="classification-row" key={item.id}>
      <span>{item.filename}<small className="table-subtext">{(item.fileSize / 1024).toFixed(1)} KB · {item.contentType} · {item.uploadedBy}</small>
        <small className="table-subtext">SHA-256: {item.sha256}</small></span>
      <button type="button" className="secondary-button" onClick={() => download(item)}>Download</button>
    </div>)}
    {canCapture && !locked && items.length < 10 && <label>Attach invoice, receipt or proof of payment
      <input type="file" accept="application/pdf,image/jpeg,image/png,image/webp" multiple disabled={busy}
        onChange={upload} />
      <small className="table-subtext">PDF, JPEG, PNG or WebP. Maximum 10 MB per file.</small>
    </label>}
  </div>;
}
