import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Stream = { id: string; code: string; name: string; active: boolean };
type Category = { id: string; streamId: string; streamCode: string; streamName: string; code: string; name: string; description: string | null; contributionType: string; active: boolean; scopeLevels: string[] };
type Target = { id: string; categoryId: string; categoryCode: string; categoryName: string; streamName: string; unitId: string; unitLevel: string; unitCode: string; unitName: string; periodStart: string; periodEnd: string; targetAmount: number; currency: string; note: string | null };

const scopeOptions = [
  { value: "NYIKA", label: "Nyika" },
  { value: "TABHERA", label: "Tabhera" },
  { value: "CELL", label: "Cell" }
];

export default function FinancialAdministration({ organisationId, authorizationHeader }: {
  organisationId: string; authorizationHeader: string;
}) {
  const [streams, setStreams] = useState<Stream[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [targets, setTargets] = useState<Target[]>([]);
  const [units, setUnits] = useState<Unit[]>([]);
  const [canConfigure, setCanConfigure] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
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

  async function reload() {
    if (!organisationId) return;
    const [streamRows, categoryRows, targetRows, access] = await Promise.all([
      request<Stream[]>(`/api/organisations/${organisationId}/financial-streams`),
      request<Category[]>(`/api/organisations/${organisationId}/contribution-categories`),
      request<Target[]>(`/api/organisations/${organisationId}/financial-targets`),
      request<boolean>(`/api/organisations/${organisationId}/financial-management-access`)
    ]);
    setStreams(streamRows);
    setCategories(categoryRows);
    setTargets(targetRows);
    setCanConfigure(access);
    if (access) {
      const unitRows = await request<Unit[]>(`/api/organisations/${organisationId}/units`);
      setUnits(unitRows.filter(unit => unit.active && ["NYIKA", "TABHERA", "CELL"].includes(unit.level)));
    } else {
      setUnits([]);
    }
  }

  useEffect(() => {
    setStreams([]); setCategories([]); setTargets([]); setUnits([]);
    setCanConfigure(false); setError(""); setNotice("");
    if (organisationId) reload().catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  async function submit(event: FormEvent<HTMLFormElement>, action: (data: FormData) => Promise<void>) {
    event.preventDefault();
    setBusy(true); setError(""); setNotice("");
    try { await action(new FormData(event.currentTarget)); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function createStream(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      await request(`/api/organisations/${organisationId}/financial-streams`, {
        method: "POST",
        body: JSON.stringify({ code: data.get("code"), name: data.get("name") })
      });
      await reload(); form.reset();
      setNotice("Financial stream added to the organisation's central configuration.");
    });
  }

  async function toggleStream(stream: Stream) {
    setBusy(true); setError(""); setNotice("");
    try {
      await request(`/api/financial-streams/${stream.id}`, {
        method: "PATCH",
        body: JSON.stringify({ name: stream.name, active: !stream.active })
      });
      await reload();
      setNotice(`Financial stream ${stream.active ? "deactivated" : "reactivated"}.`);
    } catch (e) { setError(e instanceof Error ? e.message : "Could not update financial stream."); }
    finally { setBusy(false); }
  }

  async function createCategory(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      const scopeLevels = data.getAll("scopeLevels").map(String);
      await request(`/api/organisations/${organisationId}/contribution-categories`, {
        method: "POST",
        body: JSON.stringify({
          streamId: data.get("streamId"), code: data.get("code"), name: data.get("name"),
          description: data.get("description"), contributionType: data.get("contributionType"),
          scopeLevels
        })
      });
      await reload(); form.reset();
      setNotice("Contribution category created with the selected geographical applicability.");
    });
  }

  async function toggleCategory(category: Category) {
    setBusy(true); setError(""); setNotice("");
    try {
      await request(`/api/contribution-categories/${category.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          name: category.name, description: category.description,
          contributionType: category.contributionType, active: !category.active,
          scopeLevels: category.scopeLevels
        })
      });
      await reload();
      setNotice(`Contribution category ${category.active ? "deactivated" : "reactivated"}.`);
    } catch (e) { setError(e instanceof Error ? e.message : "Could not update contribution category."); }
    finally { setBusy(false); }
  }

  async function createTarget(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      await request(`/api/organisations/${organisationId}/financial-targets`, {
        method: "POST",
        body: JSON.stringify({
          categoryId: data.get("categoryId"), unitId: data.get("unitId"),
          periodStart: data.get("periodStart"), periodEnd: data.get("periodEnd"),
          targetAmount: data.get("targetAmount"), currency: data.get("currency"),
          note: data.get("note")
        })
      });
      await reload(); form.reset();
      setNotice("Financial target recorded. Progress will become available when contribution posting is implemented.");
    });
  }

  const activeStreams = streams.filter(stream => stream.active);
  const activeCategories = categories.filter(category => category.active);

  return <section className="panel financial-administration">
    <div className="panel-title"><div><span className="panel-index">07</span><h2>Financial Administration</h2></div><span className="panel-tag">CENTRAL FINANCIAL CONTROL</span></div>
    <p className="panel-description">Centrally govern financial streams, contribution categories, geographical applicability and financial targets. This configuration is separate from membership and administrator access roles.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    {!organisationId && <div className="empty-note">Select an organisation first.</div>}
    {organisationId && <>
      <div className="overview-stat-grid">
        <article className="overview-stat"><span>FINANCIAL STREAMS</span><strong>{streams.filter(item => item.active).length.toString().padStart(2, "0")}</strong></article>
        <article className="overview-stat"><span>ACTIVE CATEGORIES</span><strong>{activeCategories.length.toString().padStart(2, "0")}</strong></article>
        <article className="overview-stat"><span>FINANCIAL TARGETS</span><strong>{targets.length.toString().padStart(2, "0")}</strong></article>
      </div>
      {!canConfigure && <div className="legacy-record-notice"><strong>SCOPED FINANCIAL VIEW</strong><span>You can view financial configuration and targets available to your assigned geographical scope. Central Administration maintains the configuration.</span></div>}
      {canConfigure && <>
        <div className="panel-divider" />
        <div className="panel-title"><div><span className="panel-index">01</span><h2>Financial streams</h2></div><span className="panel-tag">CENTRAL CATALOGUE</span></div>
        <p className="panel-description">Streams keep financial records separate for reporting and reconciliation. Main and Miscellaneous can be configured here; neither is hard-coded into the application.</p>
        <form onSubmit={createStream} className="form-stack">
          <div className="field-pair"><label>Stream code<input name="code" required maxLength={40} pattern="[A-Za-z0-9_-]{2,40}" placeholder="e.g. MAIN" /></label><label>Stream name<input name="name" required maxLength={100} placeholder="e.g. Main" /></label></div>
          <button className="secondary-button" disabled={busy}>Add financial stream</button>
        </form>
        <div className="classification-list">
          {streams.map(stream => <div className="classification-row" key={stream.id}><span className="classification-code">{stream.code}</span><span>{stream.name}</span><span className={stream.active ? "active-label" : "inactive-label"}>{stream.active ? "ACTIVE" : "INACTIVE"}</span><button type="button" className="secondary-button" disabled={busy || (stream.active && categories.some(category => category.streamId === stream.id && category.active))} onClick={() => toggleStream(stream)}>{stream.active ? "Deactivate" : "Reactivate"}</button></div>)}
          {!streams.length && <p className="empty-note">No financial streams configured. Add the approved streams before creating contribution categories.</p>}
        </div>
        <div className="panel-divider" />
        <div className="panel-title"><div><span className="panel-index">02</span><h2>Contribution categories</h2></div><span className="panel-tag">CENTRALLY GOVERNED</span></div>
        <p className="panel-description">Configure mandatory contributions, voluntary contributions and project funds. A category can apply at more than one geographical level, including Nyika and Tabhera.</p>
        <form onSubmit={createCategory} className="form-stack">
          <label>Financial stream<select name="streamId" required defaultValue=""><option value="">Select stream</option>{activeStreams.map(stream => <option key={stream.id} value={stream.id}>{stream.name} ({stream.code})</option>)}</select></label>
          <div className="field-pair"><label>Category code<input name="code" required maxLength={60} pattern="[A-Za-z0-9_-]{2,60}" placeholder="e.g. TITHING" /></label><label>Category name<input name="name" required maxLength={120} placeholder="e.g. Tithing" /></label></div>
          <label>Contribution type<select name="contributionType" defaultValue="MANDATORY"><option value="MANDATORY">Mandatory</option><option value="VOLUNTARY">Voluntary</option><option value="PROJECT">Project / special fund</option></select></label>
          <label>Description<input name="description" maxLength={500} placeholder="Optional policy description" /></label>
          <fieldset className="scope-choice"><legend>Applicable geographical levels</legend><div className="field-pair">{scopeOptions.map(option => <label className="checkbox-label" key={option.value}><input type="checkbox" name="scopeLevels" value={option.value} defaultChecked={option.value !== "CELL"} />{option.label}</label>)}</div></fieldset>
          <button className="primary-button" disabled={busy || !activeStreams.length}>Create contribution category</button>
        </form>
        <div className="table-wrap"><table><thead><tr><th>CATEGORY</th><th>STREAM</th><th>TYPE</th><th>APPLIES AT</th><th>STATUS</th><th></th></tr></thead><tbody>
          {categories.map(category => <tr key={category.id}><td><strong>{category.name}</strong><small className="table-subtext">{category.code}</small></td><td>{category.streamName}</td><td>{category.contributionType}</td><td>{category.scopeLevels.join(", ") || "—"}</td><td><span className={category.active ? "active-label" : "inactive-label"}>{category.active ? "ACTIVE" : "INACTIVE"}</span></td><td><button type="button" className="secondary-button" disabled={busy} onClick={() => toggleCategory(category)}>{category.active ? "Deactivate" : "Reactivate"}</button></td></tr>)}
          {!categories.length && <tr><td colSpan={6} className="empty-table">No contribution categories configured.</td></tr>}
        </tbody></table></div>
        <div className="panel-divider" />
        <div className="panel-title"><div><span className="panel-index">03</span><h2>Financial targets</h2></div><span className="panel-tag">GEOGRAPHICAL TARGETS</span></div>
        <p className="panel-description">Set a target amount for a contribution category and a specific Nyika, Tabhera or Cell over a defined period. The same category can have separate targets at different geographical levels.</p>
        <form onSubmit={createTarget} className="form-stack">
          <label>Contribution category<select name="categoryId" required defaultValue=""><option value="">Select category</option>{activeCategories.map(category => <option key={category.id} value={category.id}>{category.name} · {category.streamName}</option>)}</select></label>
          <label>Target geographical unit<select name="unitId" required defaultValue=""><option value="">Select Nyika, Tabhera or Cell</option>{units.filter(unit => activeCategories.some(category => category.scopeLevels.includes(unit.level))).map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
          <div className="field-pair"><label>Period starts<input name="periodStart" type="date" required /></label><label>Period ends<input name="periodEnd" type="date" required /></label></div>
          <div className="field-pair"><label>Target amount<input name="targetAmount" type="number" min="0.01" step="0.01" required placeholder="10000.00" /></label><label>Currency<select name="currency" defaultValue="ZAR"><option value="ZAR">ZAR — South African Rand</option><option value="USD">USD — US Dollar</option><option value="ZWL">ZWL — Zimbabwean Dollar</option></select></label></div>
          <label>Target note<input name="note" maxLength={1000} placeholder="Optional target context" /></label>
          <button className="primary-button" disabled={busy || !activeCategories.length || !units.length}>Record financial target</button>
        </form>
      </>}
      <div className="panel-divider" />
      <div className="register-heading"><div><h3>Target register</h3><p>Targets visible within the permitted financial scope</p></div></div>
      <div className="table-wrap"><table><thead><tr><th>CATEGORY</th><th>GEOGRAPHICAL UNIT</th><th>PERIOD</th><th>TARGET</th><th>PROGRESS</th></tr></thead><tbody>
        {targets.map(target => <tr key={target.id}><td><strong>{target.categoryName}</strong><small className="table-subtext">{target.streamName}</small></td><td>{target.unitLevel}<small className="table-subtext">{target.unitCode} — {target.unitName}</small></td><td>{target.periodStart} – {target.periodEnd}</td><td>{target.currency} {Number(target.targetAmount).toLocaleString("en-ZA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</td><td><span className="inactive-label">AWAITING CONTRIBUTION POSTING</span></td></tr>)}
        {!targets.length && <tr><td colSpan={5} className="empty-table">No financial targets have been recorded in this scope.</td></tr>}
      </tbody></table></div>
      <p className="empty-note">Target progress percentages will be calculated from posted contributions in the next financial development phase. The system does not report a fabricated 0% achievement before contribution capture exists.</p>
    </>}
  </section>;
}
