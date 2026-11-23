import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { getData } from "country-list";

type Unit = { id: string; organisationId: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
const LEVELS = [
  { key: "COUNTRY", label: "Country" },
  { key: "PROVINCE_STATE", label: "Province / State" },
  { key: "DISTRICT", label: "District" },
  { key: "NYIKA", label: "Nyika" },
];
const COUNTRY_OPTIONS = getData().sort((a, b) => a.name.localeCompare(b.name));

export default function OrganisationalHierarchy({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [countryCode, setCountryCode] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const authHeaders: Record<string, string> = authorizationHeader ? { Authorization: authorizationHeader } : {};
  const selectedUnit = units.find((unit) => unit.id === selected.filter(Boolean).slice(-1)[0]);
  const options = useMemo(() => LEVELS.map((level, index) => index === 0
    ? units.filter((unit) => unit.level === "COUNTRY")
    : units.filter((unit) => unit.level === level.key && unit.parentId === (selected[index - 1] || null))), [units, selected]);
  const deepestSelected = selected.reduce((last, id, index) => id ? index : last, -1);
  const nextLevelIndex = deepestSelected + 1;
  const parentId = deepestSelected >= 0 ? selected[deepestSelected] : null;
  const nextLevel = deepestSelected < 0 ? (units.some((unit) => unit.level === "COUNTRY") ? null : LEVELS[0]) : LEVELS[nextLevelIndex] ?? null;

  async function loadUnits() {
    if (!organisationId) { setUnits([]); setSelected([]); return; }
    const response = await fetch(`${apiBase}/api/organisations/${organisationId}/units`, { headers: authHeaders });
    if (!response.ok) throw new Error(`Could not load the geographical structure (${response.status}).`);
    setUnits(await response.json() as Unit[]);
  }
  useEffect(() => { setSelected([]); setError(""); loadUnits().catch((e: Error) => setError(e.message)); }, [organisationId]);

  function chooseLevel(index: number, id: string) {
    setSelected((current) => { const next = current.slice(0, index); next[index] = id; return next; });
    setNotice("");
  }
  async function createUnit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!organisationId || !nextLevel) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const country = COUNTRY_OPTIONS.find((item) => item.code === countryCode);
      const url = parentId ? `${apiBase}/api/units/${parentId}/children` : `${apiBase}/api/organisations/${organisationId}/units`;
      const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeaders },
        body: JSON.stringify({ code: nextLevel.key === "COUNTRY" ? countryCode : null, name: nextLevel.key === "COUNTRY" ? country?.name : name.trim() }),
      });
      if (!response.ok) { const problem = await response.json().catch(() => null); throw new Error(problem?.detail ?? `Could not create ${nextLevel.label.toLowerCase()} (${response.status}).`); }
      const created = await response.json() as Unit;
      await loadUnits();
      setSelected((current) => { const next = current.slice(0, nextLevelIndex); next[nextLevelIndex] = created.id; return next; });
      setCountryCode(""); setName(""); setNotice(`${nextLevel.label} added successfully.`);
    } catch (e) { setError(e instanceof Error ? e.message : "The geographical unit could not be created."); }
    finally { setBusy(false); }
  }
  async function toggleSelectedUnitStatus() {
    if (!selectedUnit) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiBase}/api/units/${selectedUnit.id}/status`, {
        method: "PATCH", headers: { "Content-Type": "application/json", ...authHeaders },
        body: JSON.stringify({ active: !selectedUnit.active }),
      });
      if (!response.ok) { const problem = await response.json().catch(() => null); throw new Error(problem?.detail ?? "Could not update status."); }
      const updated = await response.json() as Unit;
      setUnits((current) => current.map((unit) => unit.id === updated.id ? updated : unit));
      setNotice(`${updated.name} ${updated.active ? "reactivated" : "deactivated"}.`);
    } catch (e) { setError(e instanceof Error ? e.message : "Could not update the unit."); }
    finally { setBusy(false); }
  }
  const count = (level: string) => units.filter((unit) => unit.level === level && unit.active).length;

  return <section className="pos-window hierarchy-window central-structure">
    <div className="pos-window-title"><div><span className="pos-window-icon">ORG</span><strong>CHURCH GEOGRAPHICAL STRUCTURE</strong></div><span className="pos-window-reference">CENTRAL CONFIGURATION</span></div>
    <div className="pos-window-toolbar"><span className="toolbar-caption">ZION CHRISTIAN CHURCH</span><span className="toolbar-separator">|</span><span>ORGANISATIONAL SETUP</span><span className="toolbar-spacer" /><span className="toolbar-state">{units.length.toString().padStart(3, "0")} UNITS</span></div>
    <div className="hierarchy-window-body">
      <div className="overview-stat-grid hierarchy-counts">{LEVELS.map((level) => <article className="overview-stat" key={level.key}><span>{level.label.toUpperCase()}S</span><strong>{count(level.key).toString().padStart(3, "0")}</strong></article>)}</div>
      <div className="pos-section-heading"><span>01</span> ORGANISATIONAL HIERARCHY <small>COUNTRY → PROVINCE / STATE → DISTRICT → NYIKA</small></div>
      <p className="panel-description">Build the church structure from the top down. Each level can contain multiple records. Tabhera, Cell and household administration remain within the Nyika workspace.</p>
      <div className="hierarchy-level-grid">{LEVELS.map((level, index) => <label className="pos-field" key={level.key}>
        <span><b>{String(index + 1).padStart(2, "0")}</b> {level.label.toUpperCase()}</span>
        <select value={selected[index] ?? ""} disabled={index > 0 && !selected[index - 1]} onChange={(event) => chooseLevel(index, event.target.value)}>
          <option value="">Select {level.label.toLowerCase()}</option>
          {options[index].map((unit) => <option key={unit.id} value={unit.id}>{unit.code} — {unit.name}</option>)}
        </select>
        <small>{options[index].length} RECORD{options[index].length === 1 ? "" : "S"}</small>
      </label>)}</div>
      {selectedUnit && <div className="pos-inline-info unit-status-control"><span><strong>{selectedUnit.code} — {selectedUnit.name}</strong> · {selectedUnit.active ? "ACTIVE" : "INACTIVE"}</span><button type="button" className="secondary-button" disabled={busy} onClick={toggleSelectedUnitStatus}>{selectedUnit.active ? "DEACTIVATE" : "REACTIVATE"}</button></div>}
      <div className="pos-section-heading add-unit-heading"><span>02</span> ADD TO STRUCTURE <small>{nextLevel ? `NEXT: ${nextLevel.label.toUpperCase()}` : "SELECT A NYIKA TO VIEW ITS DETAILS"}</small></div>
      {nextLevel ? <form onSubmit={createUnit} className="pos-entry-form">
        {nextLevel.key === "COUNTRY"
          ? <label className="pos-field unit-name-field"><span>COUNTRY</span><select required value={countryCode} onChange={(event) => setCountryCode(event.target.value)}><option value="">Choose a country</option>{COUNTRY_OPTIONS.map((country) => <option key={country.code} value={country.code}>{country.name}</option>)}</select></label>
          : <label className="pos-field unit-name-field"><span>{nextLevel.label.toUpperCase()} NAME</span><input required maxLength={160} value={name} onChange={(event) => setName(event.target.value)} placeholder={`Enter ${nextLevel.label.toLowerCase()} name`} /></label>}
        <button className="primary-button pos-command-button" disabled={busy || (nextLevel.key === "COUNTRY" ? !countryCode : !name.trim())}>{busy ? "SAVING..." : `ADD ${nextLevel.label.toUpperCase()}`}</button>
      </form> : <div className="pos-inline-info">The selected Nyika is the final level managed here. Local Tabhera and Cell setup is managed by its Nyika administrator.</div>}
      <div className="pos-status-line" role="status"><span className={error ? "status-error" : notice ? "status-success" : ""}>{error || notice || "READY — SELECT A LEVEL TO CONTINUE CONFIGURATION."}</span><span>STRUCTURE SETUP</span></div>
    </div>
  </section>;
}