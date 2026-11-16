import { useEffect, useMemo, useRef, useState } from "react";

type Evidence = { id: string; filename: string; contentType: string; fileSize: number; sha256: string; uploadedAt: string };
type Corner = { x: number; y: number };
type ScanPage = { evidenceId: string; filename: string; sourceUrl: string; corners: Corner[] };
type Enhancement = "original" | "grayscale" | "enhanced";
type Document = { id: string; filename: string; fileSize: number; sha256: string; pageCount: number; createdAt: string };

const initialCorners = (): Corner[] => [
  { x: 5, y: 5 }, { x: 95, y: 5 }, { x: 95, y: 95 }, { x: 5, y: 95 }
];

function warpImage(image: HTMLImageElement, corners: Corner[], enhancement: Enhancement): HTMLCanvasElement {
  const sourceWidth = image.naturalWidth;
  const sourceHeight = image.naturalHeight;
  const top = Math.hypot((corners[1].x - corners[0].x) * sourceWidth / 100, (corners[1].y - corners[0].y) * sourceHeight / 100);
  const bottom = Math.hypot((corners[2].x - corners[3].x) * sourceWidth / 100, (corners[2].y - corners[3].y) * sourceHeight / 100);
  const left = Math.hypot((corners[3].x - corners[0].x) * sourceWidth / 100, (corners[3].y - corners[0].y) * sourceHeight / 100);
  const right = Math.hypot((corners[2].x - corners[1].x) * sourceWidth / 100, (corners[2].y - corners[1].y) * sourceHeight / 100);
  const width = Math.max(400, Math.min(2400, Math.round(Math.max(top, bottom))));
  const height = Math.max(500, Math.min(3200, Math.round(Math.max(left, right))));
  const source = document.createElement("canvas");
  source.width = sourceWidth;
  source.height = sourceHeight;
  const sourceContext = source.getContext("2d", { willReadFrequently: true });
  if (!sourceContext) throw new Error("This browser cannot process the selected image.");
  sourceContext.drawImage(image, 0, 0);
  const sourcePixels = sourceContext.getImageData(0, 0, sourceWidth, sourceHeight);
  const output = document.createElement("canvas");
  output.width = width;
  output.height = height;
  const outputContext = output.getContext("2d");
  if (!outputContext) throw new Error("This browser cannot create the scanned page.");
  const targetPixels = outputContext.createImageData(width, height);
  const [tl, tr, br, bl] = corners.map(p => ({ x: p.x * sourceWidth / 100, y: p.y * sourceHeight / 100 }));
  for (let y = 0; y < height; y++) {
    const v = y / Math.max(1, height - 1);
    for (let x = 0; x < width; x++) {
      const u = x / Math.max(1, width - 1);
      const sx = (1-u)*(1-v)*tl.x + u*(1-v)*tr.x + u*v*br.x + (1-u)*v*bl.x;
      const sy = (1-u)*(1-v)*tl.y + u*(1-v)*tr.y + u*v*br.y + (1-u)*v*bl.y;
      const ix = Math.max(0, Math.min(sourceWidth - 1, Math.round(sx)));
      const iy = Math.max(0, Math.min(sourceHeight - 1, Math.round(sy)));
      const from = (iy * sourceWidth + ix) * 4;
      const to = (y * width + x) * 4;
      targetPixels.data[to] = sourcePixels.data[from];
      targetPixels.data[to+1] = sourcePixels.data[from+1];
      targetPixels.data[to+2] = sourcePixels.data[from+2];
      targetPixels.data[to+3] = sourcePixels.data[from+3];
    }
  }
  outputContext.putImageData(targetPixels, 0, 0);
  if (enhancement !== "original") {
    const enhanced = document.createElement("canvas");
    enhanced.width = width;
    enhanced.height = height;
    const context = enhanced.getContext("2d");
    if (!context) throw new Error("This browser cannot enhance the scanned page.");
    context.filter = enhancement === "grayscale" ? "grayscale(1) contrast(1.15)" : "grayscale(1) contrast(1.55) brightness(1.08)";
    context.drawImage(output, 0, 0);
    return enhanced;
  }
  return output;
}

function canvasBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error("Could not encode the scanned page.")), "image/jpeg", 0.92));
}

export default function DepositScanner({ batchId, authorizationHeader, evidence }: {
  batchId: string; authorizationHeader: string; evidence: Evidence[];
}) {
  const [pages, setPages] = useState<ScanPage[]>([]);
  const [activeIndex, setActiveIndex] = useState(0);
  const [enhancement, setEnhancement] = useState<Enhancement>("enhanced");
  const [documents, setDocuments] = useState<Document[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const pagesRef = useRef<ScanPage[]>([]);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const imageEvidence = useMemo(() => evidence.filter(item => item.contentType.startsWith("image/")), [evidence]);
  const activePage = pages[activeIndex];

  useEffect(() => {
    setPages([]);
    setActiveIndex(0);
    setDocuments([]);
    setError("");
    fetch(`${apiBase}/api/deposit-batches/${batchId}/documents`, { headers: { Authorization: authorizationHeader } })
      .then(response => { if (!response.ok) throw new Error("Could not load scanned documents."); return response.json() as Promise<Document[]>; })
      .then(setDocuments).catch((e: Error) => setError(e.message));
  }, [batchId, authorizationHeader]);

  useEffect(() => {
    if (!activePage || !canvasRef.current) return;
    const image = new Image();
    image.onload = () => {
      try {
        const processed = warpImage(image, activePage.corners, enhancement);
        const canvas = canvasRef.current;
        if (!canvas) return;
        canvas.width = processed.width;
        canvas.height = processed.height;
        const context = canvas.getContext("2d");
        context?.drawImage(processed, 0, 0);
      } catch (e) {
        setError(e instanceof Error ? e.message : "Could not preview this page.");
      }
    };
    image.src = activePage.sourceUrl;
  }, [activePage, enhancement]);

  useEffect(() => { pagesRef.current = pages; }, [pages]);
  useEffect(() => () => pagesRef.current.forEach(page => URL.revokeObjectURL(page.sourceUrl)), []);

  async function toggleEvidence(item: Evidence, checked: boolean) {
    setError(""); setNotice("");
    if (!checked) {
      const existing = pages.find(page => page.evidenceId === item.id);
      if (existing) URL.revokeObjectURL(existing.sourceUrl);
      const next = pages.filter(page => page.evidenceId !== item.id);
      setPages(next); setActiveIndex(Math.max(0, Math.min(activeIndex, next.length - 1)));
      return;
    }
    if (pages.length >= 10) { setError("A scanned document can contain at most 10 pages."); return; }
    try {
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/evidence/${item.id}/content`, {
        headers: { Authorization: authorizationHeader }
      });
      if (!response.ok) throw new Error("Could not load the selected original photograph.");
      const blob = await response.blob();
      const page = { evidenceId: item.id, filename: item.filename, sourceUrl: URL.createObjectURL(blob), corners: initialCorners() };
      setPages(current => [...current, page]);
      setActiveIndex(pages.length);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load the selected photograph.");
    }
  }

  function updateCorner(index: number, axis: "x" | "y", value: number) {
    setPages(current => current.map((page, pageIndex) => pageIndex !== activeIndex ? page : {
      ...page, corners: page.corners.map((corner, cornerIndex) => cornerIndex !== index ? corner : { ...corner, [axis]: value })
    }));
  }

  function movePage(index: number, direction: -1 | 1) {
    const target = index + direction;
    if (target < 0 || target >= pages.length) return;
    setPages(current => {
      const next = [...current]; [next[index], next[target]] = [next[target], next[index]]; return next;
    });
    setActiveIndex(target);
  }

  async function createPdf() {
    if (!pages.length) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const form = new FormData();
      for (const page of pages) {
        const image = new Image();
        image.src = page.sourceUrl;
        await new Promise<void>((resolve, reject) => { image.onload = () => resolve(); image.onerror = () => reject(new Error("A selected page could not be decoded.")); });
        const canvas = warpImage(image, page.corners, enhancement);
        form.append("pages", await canvasBlob(canvas), `${page.filename.replace(/\.[^.]+$/, "")}-scan.jpg`);
        form.append("sourceEvidenceIds", page.evidenceId);
      }
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/documents`, {
        method: "POST", headers: { Authorization: authorizationHeader }, body: form
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `PDF creation failed (${response.status}).`);
      }
      setDocuments(await response.json() as Document[]);
      setNotice("Scanned PDF created. Original photographs remain unchanged.");
      setPages([]);
      setActiveIndex(0);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not create the scanned PDF.");
    } finally { setBusy(false); }
  }

  async function downloadDocument(item: Document) {
    try {
      const response = await fetch(`${apiBase}/api/deposit-batches/${batchId}/documents/${item.id}/content`, {
        headers: { Authorization: authorizationHeader }
      });
      if (!response.ok) throw new Error("Could not download the scanned PDF.");
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url; anchor.download = item.filename; anchor.rel = "noopener";
      document.body.appendChild(anchor); anchor.click(); anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) { setError(e instanceof Error ? e.message : "Could not download the PDF."); }
  }

  return <section className="panel-divider">
    <div className="register-heading">
      <div><h3>Document scanner</h3><p>Arrange original deposit photographs, straighten the page and create a searchable-workflow-ready PDF package.</p></div>
      <span className="count-box">{pages.length}/10 PAGES</span>
    </div>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    <div className="form-stack">
      <strong>Select original photographs</strong>
      <div className="form-grid">
        {imageEvidence.map(item => <label key={item.id} className="checkbox-row">
          <input type="checkbox" checked={pages.some(page => page.evidenceId === item.id)} disabled={busy}
            onChange={event => toggleEvidence(item, event.currentTarget.checked)} />
          <span>{item.filename}</span>
        </label>)}
        {!imageEvidence.length && <small className="table-subtext">Upload a JPEG, PNG or WebP photograph above to begin scanning.</small>}
      </div>
    </div>
    {activePage && <div className="form-stack">
      <div className="register-heading"><div><strong>Page {activeIndex + 1}: {activePage.filename}</strong><small className="table-subtext">Adjust the four document corners. The preview applies perspective correction and enhancement.</small></div>
        <div className="action-row">
          <button type="button" className="secondary-button" disabled={activeIndex === 0 || busy} onClick={() => movePage(activeIndex, -1)}>Move up</button>
          <button type="button" className="secondary-button" disabled={activeIndex === pages.length - 1 || busy} onClick={() => movePage(activeIndex, 1)}>Move down</button>
        </div>
      </div>
      <canvas ref={canvasRef} className="scanner-preview" aria-label="Corrected document preview" />
      <label>Enhancement
        <select value={enhancement} disabled={busy} onChange={event => setEnhancement(event.target.value as Enhancement)}>
          <option value="original">Original colour</option>
          <option value="grayscale">Grayscale</option>
          <option value="enhanced">High-contrast document</option>
        </select>
      </label>
      <div className="scanner-corners">
        {activePage.corners.map((corner, index) => <div className="scanner-corner" key={index}>
          <strong>{["Top left", "Top right", "Bottom right", "Bottom left"][index]}</strong>
          <label>Horizontal {Math.round(corner.x)}%
            <input type="range" min={0} max={100} value={corner.x} disabled={busy}
              onChange={event => updateCorner(index, "x", Number(event.target.value))} />
          </label>
          <label>Vertical {Math.round(corner.y)}%
            <input type="range" min={0} max={100} value={corner.y} disabled={busy}
              onChange={event => updateCorner(index, "y", Number(event.target.value))} />
          </label>
        </div>)}
      </div>
      <button type="button" className="primary-button" disabled={busy || pages.length === 0} onClick={createPdf}>
        {busy ? "Creating PDF…" : `Create ${pages.length}-page PDF`}
      </button>
    </div>}
    <div className="form-stack">
      <strong>Processed documents</strong>
      <div className="table-wrap"><table><thead><tr><th>DOCUMENT</th><th>PAGES</th><th>SIZE</th><th>CREATED</th><th></th></tr></thead><tbody>
        {documents.map(item => <tr key={item.id}><td><strong>{item.filename}</strong><small className="table-subtext">SHA-256 {item.sha256.slice(0, 16)}…</small></td>
          <td>{item.pageCount}</td><td>{(item.fileSize / 1024 / 1024).toFixed(2)} MB</td><td>{new Date(item.createdAt).toLocaleString()}</td>
          <td><button type="button" className="secondary-button" onClick={() => downloadDocument(item)}>Download PDF</button></td></tr>)}
        {!documents.length && <tr><td colSpan={5} className="empty-table">No scanned PDFs have been generated.</td></tr>}
      </tbody></table></div>
    </div>
    <small className="table-subtext">The scanner creates a derivative PDF linked to its source photographs. It does not delete, replace or alter original evidence. Boundary positioning is manually adjustable; automatic document-edge detection and OCR are not included.</small>
  </section>;
}
