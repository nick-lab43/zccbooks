import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Category = { id: string; name: string; streamName: string; active: boolean; scopeLevels: string[] };
type Member = { id: string; zccAppNumber: string | null; firstName: string; lastName: string };
type Receipt = { id: string; receiptNumber: string; memberName: string; categoryName: string; streamName: string; scopeName: string; amount: number; currency: string; contributionDate: string; paymentMethod: string; paymentReference: string | null; capturedAt: string };

export default function ContributionCapture({ organisationId, authorizationHeader }: {
  organisationId: string; authorizationHeader: string;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [cellId, setCellId] = useState("");
  const [memberId, setMemberId] = useState("");
  const [members, setMembers] = useState<Member[]>([]);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, {
      ...options,
      headers: { "Content-Type": "application/json", ...headers, ...options?.headers }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.status === 204 ? undefined as T : response.json() as Promise<T>;
  }

  useEffect(() => {
    setUnits([]); setCategories([]); setScopeUnitId(""); setCellId(""); setMemberId("");
    setMembers([]); setReceipt(null); setError("");
    if (!organisationId) return;
    Promise.all([
      request<Unit[]>(`/api/organisations/${organisationId}/units`),
      request<Category[]>(`/api/organisations/${organisationId}/contribution-categories`)
    ]).then(([unitRows, categoryRows]) => {
      setUnits(unitRows.filter(unit => unit.active && ["NYIKA", "TABHERA", "CELL"].includes(unit.level)));
      setCategories(categoryRows.filter(category => category.active));
    }).catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  const scopeUnits = units;
  const cells = useMemo(() => {
    if (!scopeUnitId) return [];
    const descendants = new Set<string>([scopeUnitId]);
    let changed = true;
    while (changed) {
      changed = false;
      for (const unit of units) {
        if (unit.parentId && descendants.has(unit.parentId) && !descendants.has(unit.id)) {
          descendants.add(unit.id); changed = true;
        }
      }
    }
    return units.filter(unit => unit.level === "CELL" && descendants.has(unit.id));
  }, [units, scopeUnitId]);
  const selectedScope = units.find(unit => unit.id === scopeUnitId);
  const applicableCategories = categories.filter(category => selectedScope && category.scopeLevels.includes(selectedScope.level));

  useEffect(() => {
    setCellId(""); setMemberId(""); setMembers([]);
    if (!cellId || !scopeUnitId) return;
  }, [scopeUnitId]);

  useEffect(() => {
    setMemberId(""); setMembers([]);
    if (!cellId || !scopeUnitId) return;
    request<Member[]>(`/api/units/${cellId}/financial-members?scopeUnitId=${scopeUnitId}`)
      .then(setMembers).catch((e: Error) => setError(e.message));
  }, [cellId, scopeUnitId, authorizationHeader]);

  async function capture(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(""); setReceipt(null);
    const data = new FormData(event.currentTarget);
    try {
      const result = await request<Receipt>(`/api/organisations/${organisationId}/contributions`, {
        method: "POST",
        body: JSON.stringify({
          memberId, categoryId: data.get("categoryId"), scopeUnitId,
          amount: data.get("amount"), contributionDate: data.get("contributionDate"),
          paymentMethod: data.get("paymentMethod"), paymentReference: data.get("paymentReference"),
          note: data.get("note"), idempotencyKey: crypto.randomUUID()
        })
      });
      setReceipt(result);
      event.currentTarget.reset();
      setMemberId("");
    } catch (e) { setError(e instanceof Error ? e.message : "Contribution could not be posted."); }
    finally { setBusy(false); }
  }

  return <section className="panel">
    <div className="panel-divider" />
    <div className="panel-title"><div><span className="panel-index">04</span><h2>Contribution capture</h2></div><span className="panel-tag">SCOPED POSTING</span></div>
    <p className="panel-description">Record a member contribution against an approved category and an authorised Nyika, Tabhera or Cell scope. Each successful posting creates a traceable receipt.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    <form onSubmit={capture} className="form-stack">
      <label>Financial scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)} required><option value="">Select assigned scope</option>{scopeUnits.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
      <label>Member's Cell<select value={cellId} onChange={e => setCellId(e.target.value)} required disabled={!scopeUnitId}><option value="">Select Cell</option>{cells.map(unit => <option key={unit.id} value={unit.id}>{unit.code} — {unit.name}</option>)}</select></label>
      <label>Member<select value={memberId} onChange={e => setMemberId(e.target.value)} required disabled={!cellId}><option value="">Select active member</option>{members.map(member => <option key={member.id} value={member.id}>{member.lastName}, {member.firstName}{member.zccAppNumber ? ` · ${member.zccAppNumber}` : ""}</option>)}</select></label>
      <label>Contribution category<select name="categoryId" required defaultValue="" disabled={!applicableCategories.length}><option value="">Select approved category</option>{applicableCategories.map(category => <option key={category.id} value={category.id}>{category.name} · {category.streamName}</option>)}</select></label>
      <div className="field-pair"><label>Amount (ZAR)<input name="amount" type="number" min="0.01" step="0.01" required placeholder="0.00" /></label><label>Contribution date<input name="contributionDate" type="date" required defaultValue={new Date().toISOString().slice(0, 10)} /></label></div>
      <div className="field-pair"><label>Payment method<select name="paymentMethod" defaultValue="CASH"><option value="CASH">Cash</option><option value="BANK_TRANSFER">Bank transfer</option><option value="CARD">Card</option><option value="MOBILE_MONEY">Mobile money</option><option value="OTHER">Other</option></select></label><label>Payment reference<input name="paymentReference" maxLength={120} placeholder="Optional reference" /></label></div>
      <label>Note<input name="note" maxLength={500} placeholder="Optional contribution note" /></label>
      <button className="primary-button" disabled={busy || !memberId || !applicableCategories.length}>{busy ? "Posting contribution…" : "Post contribution and issue receipt"}</button>
    </form>
    {receipt && <div className="legacy-record-notice" role="status">
      <strong>RECEIPT ISSUED · {receipt.receiptNumber}</strong>
      <span>{receipt.memberName} · {receipt.categoryName} · {receipt.currency} {Number(receipt.amount).toLocaleString("en-ZA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} · {receipt.contributionDate}</span>
      <button type="button" className="secondary-button" onClick={() => window.print()}>Print receipt</button>
    </div>}
  </section>;
}
