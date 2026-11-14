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

export default function OrganisationalHierarchy({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
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
  const authHeaders: Record<string, string> = authorizationHeader ? { Authorization: authorizationHeader } : {};
  const firstVisibleLevelIndex = units.length
    ? Math.min(...units.map((unit) => LEVELS.findIndex((level) => level.key === unit.level)).filter((index) => index >= 0))
    : 0;
  // Keep empty descendant levels available so administrators can create the
  // next unit. Starting at the first visible level preserves scoped Nyika views.
  const visibleLevels = LEVELS.slice(firstVisibleLevelIndex);
  const cellLevelIndex = visibleLevels.findIndex((level) => level.key === "CELL");
  const selectedCellId = cellLevelIndex >= 0 ? selected[cellLevelIndex] || "" : "";
  const selectedUnit = units.find((unit) => unit.id === selected.filter(Boolean).at(-1));

  async function loadUnits() {
    if (!organisationId) { setUnits([]); setSelected([]); return; }
    const response = await fetch(`${apiBase}/api/organisations/${organisationId}/units`, { headers: authHeaders });
    if (!response.ok) throw new Error(`Could not load hierarchy (${response.status}).`);
    setUnits(await response.json() as Unit[]);
  }

  async function loadHouseholds(cellId: string) {
    if (!cellId) { setHouseholds([]); return; }
    const response = await fetch(`${apiBase}/api/units/${cellId}/households`, { headers: authHeaders });
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

  const options = useMemo(() => visibleLevels.map((level, index) => {
    if (index === 0) {
      const visibleIds = new Set(units.map((unit) => unit.id));
      return units.filter((unit) => !unit.parentId || !visibleIds.has(unit.parentId));
    }
    const parentId = selected[index - 1] || null;
    return units.filter((unit) => unit.level === level.key && unit.parentId === parentId);
  }), [units, selected, visibleLevels]);

  const deepestSelected = selected.reduce((last, id, index) => id ? index : last, -1);
  const nextLevelIndex = deepestSelected + 1;
  const parentId = deepestSelected >= 0 ? selected[deepestSelected] : null;
  const nextLevel = deepestSelected < 0
    ? (units.length === 0 ? visibleLevels[0] : null)
    : visibleLevels[nextLevelIndex] ?? null;

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
        method: "POST", headers: { "Content-Type": "application/json", ...authHeaders },
        body: JSON.stringify({ code, name }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Could not create unit (${response.status}).`);
      }
      const created = await response.json() as Unit;
      const refreshed = await fetch(`${apiBase}/api/organisations/${organisationId}/units`, { headers: authHeaders });
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

  async function toggleSelectedUnitStatus() {
    if (!selectedUnit) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiBase}/api/units/${selectedUnit.id}/status`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", ...authHeaders },
        body: JSON.stringify({ active: !selectedUnit.active }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Could not update unit status (${response.status}).`);
      }
      const updated = await response.json() as Unit;
      setUnits((current) => current.map((unit) => unit.id === updated.id ? updated : unit));
      setNotice(`${updated.name} ${updated.active ? "reactivated" : "deactivated"}.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The unit status could not be changed.");
    } finally { setBusy(false); }
  }

  async function createHousehold(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedCellId) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await fetch(`${apiBase}/api/units/${selectedCellId}/households`, {
        method: "POST", headers: { "Content-Type": "application/json", ...authHeaders },
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
        <div className="pos-section-heading"><span>01</span> GEOGRAPHICAL LEVELS <small>{visibleLevels.map((level) => level.label.toUpperCase()).join(" → ") || "NO UNITS REGISTERED"}</small></div>
        {!organisationId && <div className="pos-inline-warning">Select or create an organisation before maintaining its hierarchy.</div>}
        {organisationId && <>
          {!units.length && <div className="pos-inline-warning">No geographical units are available within your assigned scope, or you do not have permission to view them.</div>}
          <div className="hierarchy-level-grid">
            {visibleLevels.map((level, index) => (
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
          {selectedUnit && <div className="pos-inline-info unit-status-control">
            <span><strong>{selectedUnit.code} — {selectedUnit.name}</strong> · {selectedUnit.active ? "ACTIVE" : "INACTIVE"}</span>
            <button type="button" className="secondary-button" disabled={busy}
              onClick={toggleSelectedUnitStatus}>
              {selectedUnit.active ? "DEACTIVATE SELECTED UNIT" : "REACTIVATE SELECTED UNIT"}
            </button>
          </div>}
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
