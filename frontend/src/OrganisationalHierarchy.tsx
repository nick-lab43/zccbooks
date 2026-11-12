import { useEffect, useMemo, useState } from "react";
import type { CSSProperties, FormEvent } from "react";

type Unit = {
  id: string;
  organisationId: string;
  parentId: string | null;
  level: string;
  code: string;
  name: string;
  active: boolean;
};

const LEVELS = [
  { key: "COUNTRY", label: "Country" },
  { key: "PROVINCE_STATE", label: "Province / State" },
  { key: "DISTRICT", label: "District" },
  { key: "NYIKA", label: "Nyika" },
  { key: "TABHERA", label: "Tabhera" },
  { key: "CELL", label: "Cell" },
  { key: "TABHERA_YEMUMBA", label: "Tabhera yemumba" },
];

const fieldStyle: CSSProperties = {
  display: "block", width: "100%", padding: "10px 12px", marginTop: 6,
  border: "1px solid #cbd3dc", borderRadius: 4, background: "#fff", color: "#172b43",
};

export default function OrganisationalHierarchy({ organisationId }: { organisationId: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

  async function loadUnits() {
    if (!organisationId) { setUnits([]); setSelected([]); return; }
    const response = await fetch(`${apiBase}/api/organisations/${organisationId}/units`);
    if (!response.ok) throw new Error(`Could not load hierarchy (${response.status}).`);
    setUnits(await response.json() as Unit[]);
  }

  useEffect(() => {
    setSelected([]);
    setError("");
    loadUnits().catch((e: Error) => setError(e.message));
  }, [organisationId]);

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
        method: "POST",
        headers: { "Content-Type": "application/json" },
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
      setCode(""); setName("");
      setNotice(`${nextLevel.label} created.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The unit could not be created.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section style={{ margin: "24px 0", padding: 22, background: "#fff", border: "1px solid #cbd3dc", borderTop: "4px solid #b08b48", borderRadius: 5 }}>
      <div style={{ display: "flex", justifyContent: "space-between", gap: 12, alignItems: "start", flexWrap: "wrap" }}>
        <div>
          <div style={{ color: "#8b713f", fontSize: 11, letterSpacing: 1.5, fontWeight: 700 }}>ORGANISATIONAL STRUCTURE</div>
          <h2 style={{ margin: "5px 0", color: "#172b43", fontFamily: "Georgia, serif" }}>Church hierarchy</h2>
          <p style={{ margin: 0, color: "#5b6877", fontSize: 13 }}>Build the church structure from Country down to Tabhera yemumba.</p>
        </div>
        <span style={{ fontSize: 11, color: "#536273", border: "1px solid #d6dce3", padding: "6px 9px" }}>7 LEVELS</span>
      </div>
      {!organisationId && <p style={{ color: "#8a5b20" }}>Create or select an organisation to configure its hierarchy.</p>}
      {organisationId && (
        <>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: 12, marginTop: 18 }}>
            {LEVELS.map((level, index) => (
              <label key={level.key} style={{ color: "#34465a", fontSize: 12, fontWeight: 700 }}>
                {index + 1}. {level.label}
                <select
                  value={selected[index] ?? ""}
                  disabled={index > 0 && !selected[index - 1]}
                  onChange={(event) => chooseLevel(index, event.target.value)}
                  style={fieldStyle}
                >
                  <option value="">Select {level.label.toLowerCase()}</option>
                  {options[index].map((unit) => <option key={unit.id} value={unit.id}>{unit.code} — {unit.name}</option>)}
                </select>
              </label>
            ))}
          </div>
          <div style={{ marginTop: 18, padding: 16, background: "#f4f6f8", border: "1px solid #e0e5ea" }}>
            <strong style={{ color: "#172b43", fontSize: 14 }}>
              {nextLevel ? `Add ${nextLevel.label}` : "Lowest level selected"}
            </strong>
            {nextLevel ? (
              <form onSubmit={createUnit} style={{ display: "grid", gridTemplateColumns: "minmax(130px, 1fr) minmax(180px, 2fr) auto", gap: 10, alignItems: "end", marginTop: 10 }}>
                <label style={{ fontSize: 12, color: "#34465a" }}>Code
                  <input required maxLength={60} value={code} onChange={(e) => setCode(e.target.value)} placeholder="Unique code" style={fieldStyle}/>
                </label>
                <label style={{ fontSize: 12, color: "#34465a" }}>Name
                  <input required maxLength={160} value={name} onChange={(e) => setName(e.target.value)} placeholder={`${nextLevel.label} name`} style={fieldStyle}/>
                </label>
                <button disabled={busy} className="primary-button" style={{ minHeight: 39 }}>{busy ? "Saving..." : `Add ${nextLevel.label}`}</button>
              </form>
            ) : <p style={{ marginBottom: 0, color: "#536273", fontSize: 13 }}>Tabhera yemumba is the final level. It cannot contain another organisational unit.</p>}
          </div>
          {(error || notice) && <p role="status" style={{ color: error ? "#a33131" : "#286b4a", marginBottom: 0 }}>{error || notice}</p>}
        </>
      )}
    </section>
  );
}
