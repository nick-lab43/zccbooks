import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = {
  id: string; organisationId: string; parentId: string | null;
  level: string; code: string; name: string; active: boolean;
};
type Household = { id: string; code: string; name: string; householdHeadMemberId: string | null; active: boolean };

const LEVELS = [
  { key: "COUNTRY", label: "Country" },
  { key: "PROVINCE_STATE", label: "Province / State" },
  { key: "DISTRICT", label: "District" },
  { key: "NYIKA", label: "Nyika" },
  { key: "TABHERA", label: "Tabhera" },
  { key: "CELL", label: "Cell" },
];

export default function OrganisationalHierarchy({ organisationId }: { organisationId: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [householdCode, setHouseholdCode] = useState("");
  const [householdName, setHouseholdName] = useState("");
  const [households, setHouseholds] = useState<Household[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const selectedCellId = selected[5] || "";

  async function loadUnits() {
    if (!organisationId) { setUnits([]); setSelected([]); return; }
    const response = await fetch(`${apiBase}/api/organisations/${organisationId}/units`);
    if (!response.ok) throw new Error(`Could not load hierarchy (${response.status}).`);
    setUnits(await response.json() as Unit[]);
  }

  async function loadHouseholds(cellId: string) {
    if (!cellId) { setHouseholds([]); return; }
    const response = await fetch(`${apiBase}/api/units/${cellId}/households`);
    if (!response.ok) throw new Error(`Could not load households (${response.status}).`);
    setHouseholds(await response.json() as Household[]);
  }

  useEffect(() => {
    setSelected([]); setError(""); setHouseholds([]);
    loadUnits().catch((e: Error) => setError(e.message));
  }, [organisationId]);

  useEffect(() => {
    loadHouseholds(selectedCellId).catch((e: Error) => setError(e.message));
  }, [selectedCellId]);

  const options = useMemo(() => LEVELS.map((level, index) => {
    const parentId = index === 0 ? null : selected[index - 1] || null;
    return units.filter((unit) => unit.level === level.key && unit.parentId === parentId);
  }), [units, selected]);

  const deepestSelected = selected.reduce((last, id, index) => id ? index : last, -1);
  const nextLevelIndex = deepestSelected + 1;
  const parentId = deepestSelected >= 0 ? selected[deepestSelected] : null;
  const nextLevel = LEVELS[nextLevelIndex];

  function chooseLevel(index: number, id: string) {
    setSelected((current) => {
      const next = current.slice(0, index);
      next[index] = id;
      return next;
    });
    setNotice("");
  }

  async function createUnit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!organisationId || !nextLevel) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const url = parentId
        ? `${apiBase}/api/units/${parentId}/children`
        : `${apiBase}/api/organisations/${organisationId}/units`;
      const response = await fetch(url, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code, name }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Could not create unit (${response.status}).`);
      }
      const created = await response.json() as Unit;
      const refreshed = await fetch(`${apiBase}/api/organisations/${organisationId}/units`);
      if (!refreshed.ok) throw new Error("Unit was created, but the hierarchy could not be refreshed.");
      setUnits(await refreshed.json() as Unit[]);
      setSelected((current) => {
        const next = current.slice(0, nextLevelIndex);
        next[nextLevelIndex] = created.id;
        return next;
      });
      setCode(""); setName(""); setNotice(`${nextLevel.label} created.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The unit could not be created.");
    } finally { setBusy(false); }
  }

  async function createHousehold(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedCellId) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiBase}/api/units/${selectedCellId}/households`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: householdCode, name: householdName }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Could not create household (${response.status}).`);
      }
      await loadHouseholds(selectedCellId);
      setHouseholdCode(""); setHouseholdName(""); setNotice("Household created under the selected Cell.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "The household could not be created.");
    } finally { setBusy(false); }
  }

  return (
    <section className="pos-window hierarchy-window">
      <div className="pos-window-title">
        <div><span className="pos-window-icon">ORG</span><strong>ORGANISATIONAL STRUCTURE MAINTENANCE</strong></div>
        <span className="pos-window-reference">FILE: ORG-001</span>
      </div>
      <div className="pos-window-toolbar">
        <span className="toolbar-caption">MASTER DATA</span><span className="toolbar-separator">|</span>
        <span>GEOGRAPHICAL HIERARCHY</span><span className="toolbar-spacer" />
        <span className="toolbar-state">{units.length.toString().padStart(3, "0")} UNITS LOADED</span>
      </div>
      <div className="hierarchy-window-body">
        <div className="pos-section-heading"><span>01</span> GEOGRAPHICAL LEVELS <small>COUNTRY → PROVINCE / STATE → DISTRICT → NYIKA → TABHERA → CELL</small></div>
        {!organisationId && <div className="pos-inline-warning">Select or create an organisation before maintaining its hierarchy.</div>}
        {organisationId && <>
          <div className="hierarchy-level-grid">
            {LEVELS.map((level, index) => (
              <label className="pos-field" key={level.key}>
                <span><b>{String(index + 1).padStart(2, "0")}</b> {level.label.toUpperCase()}</span>
                <select value={selected[index] ?? ""} disabled={index > 0 && !selected[index - 1]}
                  onChange={(event) => chooseLevel(index, event.target.value)}>
                  <option value="">-- SELECT --</option>
                  {options[index].map((unit) => <option key={unit.id} value={unit.id}>{unit.code} - {unit.name}</option>)}
                </select>
                <small>{options[index].length} RECORD{options[index].length === 1 ? "" : "S"}</small>
              </label>
            ))}
          </div>
          <div className="pos-section-heading add-unit-heading"><span>02</span> ADD GEOGRAPHICAL UNIT
            <small>{nextLevel ? `NEXT LEVEL: ${nextLevel.label.toUpperCase()}` : "CELL SELECTED — GEOGRAPHICAL HIERARCHY COMPLETE"}</small>
          </div>
          {nextLevel ? (
            <form onSubmit={createUnit} className="pos-entry-form">
              <label className="pos-field"><span>UNIT CODE</span><input required maxLength={60} value={code} onChange={(e) => setCode(e.target.value)} placeholder="ENTER CODE" /></label>
              <label className="pos-field unit-name-field"><span>UNIT NAME / DESCRIPTION</span><input required maxLength={160} value={name} onChange={(e) => setName(e.target.value)} placeholder={`ENTER ${nextLevel.label.toUpperCase()} NAME`} /></label>
              <button className="primary-button pos-command-button" disabled={busy}>{busy ? "SAVING..." : "SAVE RECORD"}</button>
            </form>
          ) : <div className="pos-inline-info">CELL IS THE LOWEST GEOGRAPHICAL LEVEL. HOUSEHOLDS ARE MAINTAINED SEPARATELY BELOW.</div>}
          {selectedCellId && <>
            <div className="pos-section-heading add-unit-heading"><span>03</span> TABHERA YEMUMBA — HOUSEHOLD REGISTER
              <small>{households.length.toString().padStart(3, "0")} HOUSEHOLDS IN SELECTED CELL</small>
            </div>
            <form onSubmit={createHousehold} className="pos-entry-form">
              <label className="pos-field"><span>HOUSEHOLD CODE</span><input required maxLength={60} value={householdCode} onChange={(e) => setHouseholdCode(e.target.value)} placeholder="ENTER CODE" /></label>
              <label className="pos-field unit-name-field"><span>HOUSEHOLD / FAMILY NAME</span><input required maxLength={160} value={householdName} onChange={(e) => setHouseholdName(e.target.value)} placeholder="ENTER FAMILY DESIGNATION" /></label>
              <button className="primary-button pos-command-button" disabled={busy}>{busy ? "SAVING..." : "ADD HOUSEHOLD"}</button>
            </form>
            <div className="table-wrap household-table"><table><thead><tr><th>HOUSEHOLD CODE</th><th>HOUSEHOLD / FAMILY</th><th>STATUS</th></tr></thead><tbody>
              {households.map((household) => <tr key={household.id}><td><strong>{household.code}</strong></td><td>{household.name}</td><td><span className="active-label">{household.active ? "ACTIVE" : "INACTIVE"}</span></td></tr>)}
              {!households.length && <tr><td colSpan={3} className="empty-table">No households registered in this Cell.</td></tr>}
            </tbody></table></div>
          </>}
          <div className="pos-status-line" role="status">
            <span className={error ? "status-error" : notice ? "status-success" : ""}>{error || notice || "READY — SELECT A GEOGRAPHICAL LEVEL OR MAINTAIN HOUSEHOLDS UNDER A CELL."}</span>
            <span>F7: SAVE RECORD</span>
          </div>
        </>}
      </div>
    </section>
  );
}
