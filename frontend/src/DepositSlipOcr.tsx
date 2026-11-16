import { useEffect, useMemo, useState } from "react";
import { createWorker } from "tesseract.js";

type Evidence = { id: string; filename: string; contentType: string };
type DepositContext = { depositDate: string; bankReference: string; expectedAmount: number };
type SavedReview = { id: string; evidenceId: string; reviewedAmount: number; reviewedBankReference: string; reviewedDepositDate: string; amountMatches: boolean; referenceMatches: boolean; dateMatches: boolean; reviewedBy: string; reviewedAt: string };
type ExtractedFields = { amount: string; reference: string; date: string };
type Comparison = "match" | "mismatch" | "missing";

function extractFields(text: string): ExtractedFields {
  const amountMatch = text.match(/(?:R\s*)?([0-9][0-9, ]*\.\d{2})/i);
  const referenceMatch = text.match(/(?:bank\s*)?(?:deposit\s*)?(?:reference|ref(?:erence)?\s*(?:no|number)?|trace)\s*[:#-]?\s*([A-Z0-9/-]{4,})/i);
  const dateMatch = text.match(/\b(\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})\b/);
  return {
    amount: amountMatch ? amountMatch[1].replace(/[ ,]/g, "") : "",
    reference: referenceMatch ? referenceMatch[1].trim() : "",
    date: dateMatch ? dateMatch[1] : ""
  };
}

function normaliseDate(value: string): string {
  const trimmed = value.trim();
  const iso = trimmed.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$/);
  if (iso) return `${iso[1]}-${iso[2].padStart(2, "0")}-${iso[3].padStart(2, "0")}`;
  const local = trimmed.match(/^(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})$/);
  if (!local) return "";
  const year = local[3].length === 2 ? `20${local[3]}` : local[3];
  return `${year}-${local[2].padStart(2, "0")}-${local[1].padStart(2, "0")}`;
}

function comparison(value: string, expected: string, kind: "amount" | "reference" | "date"): Comparison {
  if (!value.trim()) return "missing";
  if (kind === "amount") {
    const actual = Number(value.replace(/[R,\s]/gi, ""));
    return Number.isFinite(actual) && Math.abs(actual - Number(expected)) < 0.005 ? "match" : "mismatch";
  }
  if (kind === "date") return normaliseDate(value) === expected ? "match" : "mismatch";
  return value.replace(/\s+/g, "").toLowerCase() === expected.replace(/\s+/g, "").toLowerCase() ? "match" : "mismatch";
}

export default function DepositSlipOcr({ batchId, authorizationHeader, evidence }: {
  batchId: string; authorizationHeader: string; evidence: Evidence[];
}) {
  const [selectedId, setSelectedId] = useState("");
  const [context, setContext] = useState<DepositContext | null>(null);
  const [fields, setFields] = useState<ExtractedFields>({ amount: "", reference: "", date: "" });
  const [rawText, setRawText] = useState("");
  const [progress, setProgress] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [confirmedOriginal, setConfirmedOriginal] = useState(false);
  const [savingReview, setSavingReview] = useState(false);
  const [reviews, setReviews] = useState<SavedReview[]>([]);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const images = useMemo(() => evidence.filter(item => item.contentType.startsWith("image/")), [evidence]);

  useEffect(() => {
    setSelectedId("");
    setContext(null);
    setNotice("");
    setConfirmedOriginal(false);
    setReviews([]);
    setFields({ amount: "", reference: "", date: "" });
    setRawText("");
    setError("");
    fetch(`${apiBase}/api/deposit-batches/${batchId}/documents/ocr-context`, {
      headers: { Authorization: authorizationHeader }
    }).then(response => {
      if (!response.ok) throw new Error("Could not load the deposit details for comparison.");
      return response.json() as Promise<DepositContext>;
    }).then(setContext).catch((e: Error) => setError(e.message));
    fetch(`${apiBase}/api/deposit-batches/${batchId}/documents/ocr-reviews`, {
      headers: { Authorization: authorizationHeader }
    }).then(response => response.ok ? response.json() as Promise<SavedReview[]> : [])
      .then(setReviews).catch(() => setReviews([]));
  }, [batchId, authorizationHeader]);

  async function runOcr() {
    const selected = images.find(item => item.id === selectedId);
    if (!selected) return;
    setBusy(true); setError(""); setNotice(""); setProgress(0); setRawText("");
    let worker: Awaited<ReturnType<typeof createWorker>> | null = null;
    try {
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/evidence/${selected.id}/content`, {
        headers: { Authorization: authorizationHeader }
      });
      if (!response.ok) throw new Error("Could not load the original deposit photograph.");
      const imageUrl = URL.createObjectURL(await response.blob());
      try {
        const image = new Image();
        image.src = imageUrl;
        await new Promise<void>((resolve, reject) => {
          image.onload = () => resolve();
          image.onerror = () => reject(new Error("The selected photograph could not be decoded."));
        });
        const canvas = document.createElement("canvas");
        const scale = Math.min(1, 2400 / Math.max(image.naturalWidth, image.naturalHeight));
        canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
        canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
        const canvasContext = canvas.getContext("2d");
        if (!canvasContext) throw new Error("This browser cannot prepare the photograph for OCR.");
        canvasContext.drawImage(image, 0, 0, canvas.width, canvas.height);
        worker = await createWorker("eng", 1, {
          logger: message => {
            if (message.status === "recognizing text") setProgress(Math.round(message.progress * 100));
          }
        });
        const result = await worker.recognize(canvas);
        const text = result.data.text.trim();
        setRawText(text);
        setFields(extractFields(text));
        setNotice("Text extracted. Check every field against the original slip before relying on it.");
      } finally {
        URL.revokeObjectURL(imageUrl);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "OCR could not process this photograph.");
    } finally {
      if (worker) await worker.terminate();
      setBusy(false);
    }
  }

  async function saveReview() {
    const selected = images.find(item => item.id === selectedId);
    if (!selected || !context || !confirmedOriginal) return;
    setSavingReview(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/documents/ocr-reviews`, {
        method: "POST",
        headers: { Authorization: authorizationHeader, "Content-Type": "application/json" },
        body: JSON.stringify({
          evidenceId: selected.id,
          reviewedAmount: fields.amount,
          reviewedBankReference: fields.reference,
          reviewedDepositDate: normaliseDate(fields.date),
          ocrAmountCandidate: fields.amount,
          ocrReferenceCandidate: fields.reference,
          ocrDateCandidate: fields.date,
          operatorConfirmedOriginal: true
        })
      });
      if (!response.ok) {
        const message = await response.text();
        throw new Error(message || "The review could not be saved.");
      }
      const saved = await response.json() as SavedReview;
      setReviews(current => [saved, ...current]);
      setNotice("Review recorded in the audit history. The deposit itself was not changed.");
      setConfirmedOriginal(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The review could not be saved.");
    } finally {
      setSavingReview(false);
    }
  }

  const checks = context ? [
    { label: "Deposit amount", value: fields.amount, expected: context.expectedAmount.toFixed(2), result: comparison(fields.amount, context.expectedAmount.toFixed(2), "amount") },
    { label: "Bank reference", value: fields.reference, expected: context.bankReference, result: comparison(fields.reference, context.bankReference, "reference") },
    { label: "Deposit date", value: fields.date, expected: context.depositDate, result: comparison(fields.date, context.depositDate, "date") }
  ] : [];

  return <section className="panel-divider">
    <div className="register-heading">
      <div><h3>Deposit-slip text extraction</h3><p>Read a photograph and compare suggested values with the deposit record.</p></div>
      <span className="count-box">ASSISTED OCR</span>
    </div>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    <div className="form-grid">
      <label>Photograph
        <select value={selectedId} disabled={busy} onChange={event => setSelectedId(event.target.value)}>
          <option value="">Select an image</option>
          {images.map(item => <option key={item.id} value={item.id}>{item.filename}</option>)}
        </select>
      </label>
      <div className="form-stack">
        <span className="table-subtext">OCR runs in this browser. The source image is not uploaded to an OCR provider.</span>
        <button type="button" className="primary-button" disabled={!selectedId || busy} onClick={runOcr}>
          {busy ? `Reading document… ${progress}%` : "Extract deposit details"}
        </button>
      </div>
    </div>
    {context && <div className="form-stack">
      <strong>Extracted values — editable for review</strong>
      <div className="form-grid">
        <label>Amount (R)
          <input value={fields.amount} disabled={busy} onChange={event => setFields(current => ({ ...current, amount: event.target.value }))} />
        </label>
        <label>Bank reference
          <input value={fields.reference} disabled={busy} onChange={event => setFields(current => ({ ...current, reference: event.target.value }))} />
        </label>
        <label>Date as printed
          <input value={fields.date} disabled={busy} onChange={event => setFields(current => ({ ...current, date: event.target.value }))} />
        </label>
      </div>
      <div className="table-wrap"><table><thead><tr><th>FIELD</th><th>DEPOSIT RECORD</th><th>OCR / REVIEWED VALUE</th><th>COMPARISON</th></tr></thead><tbody>
        {checks.map(item => <tr key={item.label}>
          <td><strong>{item.label}</strong></td><td>{item.expected}</td><td>{item.value || "Not detected"}</td>
          <td><span className={`ocr-status ${item.result}`}>{item.result === "match" ? "Matches" : item.result === "mismatch" ? "Mismatch" : "Not detected"}</span></td>
        </tr>)}
      </tbody></table></div>
      <small className="table-subtext">A match is a text comparison only. It does not verify that the bank received the money, that the slip is genuine, or that the amount is correct. OCR output never changes the deposit record or reconciles a payment.</small>
      <label className="checkbox-row"><input type="checkbox" checked={confirmedOriginal} disabled={savingReview || !selectedId || !fields.amount || !fields.reference || !normaliseDate(fields.date)} onChange={event => setConfirmedOriginal(event.target.checked)} />I checked these values against the original deposit slip.</label>
      <button type="button" className="primary-button" disabled={!confirmedOriginal || savingReview || !selectedId || !fields.amount || !fields.reference || !normaliseDate(fields.date)} onClick={saveReview}>{savingReview ? "Saving review…" : "Record reviewed values"}</button>
      {reviews.length > 0 && <div className="form-stack"><strong>Previous review history</strong><div className="table-wrap"><table><thead><tr><th>REVIEWED</th><th>AMOUNT</th><th>REFERENCE</th><th>DATE</th><th>REVIEWER</th></tr></thead><tbody>
        {reviews.map(review => <tr key={review.id}><td>{new Date(review.reviewedAt).toLocaleString()}</td><td><span className={review.amountMatches ? "ocr-status match" : "ocr-status mismatch"}>{review.reviewedAmount.toFixed(2)}</span></td><td><span className={review.referenceMatches ? "ocr-status match" : "ocr-status mismatch"}>{review.reviewedBankReference}</span></td><td><span className={review.dateMatches ? "ocr-status match" : "ocr-status mismatch"}>{review.reviewedDepositDate}</span></td><td>{review.reviewedBy}</td></tr>)}
      </tbody></table></div><small className="table-subtext">Review entries are append-only. A later correction creates a new entry; previous reviews remain visible.</small></div>}
    </div>}
    {rawText && <details className="form-stack"><summary>Full OCR text</summary><pre className="ocr-raw-text">{rawText}</pre></details>}
  </section>;
}
