import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Classification = { id: string; code: string; name: string; active: boolean };
type Member = {
  id: string; zccAppNumber: string | null; eliteNumber: string | null;
  firstName: string; lastName: string; classificationId: string | null;
  classificationName: string | null; active: boolean; cellId: string | null; cellName: string | null;
};

export default function NyikaAdminWorkspace({ organisationId, authorizationHeader }: {
  organisationId: string; authorizationHeader: string;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [members, setMembers] = useState<Member[]>([]);
  const [classifications, setClassifications] = useState<Classification[]>([]);
  const [nyikaId, setNyikaId] = useState("");
  const [tabheraId, setTabheraId] = useState("");
  const [cellId, setCellId] = useState("");
  const [memberCellId, setMemberCellId] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [classificationFilter, setClassificationFilter] = useState("");
  const [cellFilter, setCellFilter] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, {
      ...options, headers: { "Content-Type": "application/json", ...headers, ...options?.headers }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.status === 204 ? undefined as T : response.json() as Promise<T>;
  }

  async function reload() {
    if (!organisationId) return;
    const [rows, configuredClassifications] = await Promise.all([
      request<Unit[]>(`/api/organisations/${organisationId}/units`),
      request<Classification[]>(`/api/organisations/${organisationId}/classifications`)
    ]);
    const nyika = rows.find(unit => unit.level === "NYIKA" && unit.active);
    setUnits(rows);
    setClassifications(configuredClassifications.filter(item => item.active));
    setNyikaId(nyika?.id ?? "");
    const tabs = rows.filter(unit => unit.level === "TABHERA" && unit.active && unit.parentId === nyika?.id);
    setTabheraId(current => tabs.some(tab => tab.id === current) ? current : tabs[0]?.id ?? "");
    if (nyika) {
      const memberRows = await request<Member[]>(`/api/nyikas/${nyika.id}/members`);
      setMembers(memberRows);
    } else {
      setMembers([]);
    }
  }

  useEffect(() => {
    setUnits([]); setMembers([]); setClassifications([]); setSelectedIds([]);
    setError(""); setNotice(""); setClassificationFilter(""); setCellFilter("");
    if (organisationId) reload().catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  const tabheras = units.filter(unit => unit.level === "TABHERA" && unit.active && unit.parentId === nyikaId);
  const cells = units.filter(unit => unit.level === "CELL" && unit.active);
  const visibleMembers = members.filter(member => {
    const term = search.trim().toLowerCase();
    const matchesSearch = !term || `${member.firstName} ${member.lastName} ${member.zccAppNumber ?? ""} ${member.eliteNumber ?? ""}`.toLowerCase().includes(term);
    return matchesSearch
      && (!classificationFilter || member.classificationId === classificationFilter)
      && (!cellFilter || member.cellId === cellFilter);
  });
  const unassignedCount = members.filter(member => !member.cellId).length;
  const assignedCount = members.length - unassignedCount;

  async function submit(event: FormEvent<HTMLFormElement>, action: (data: FormData) => Promise<void>) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    try { await action(new FormData(event.currentTarget)); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function createCell(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      if (!tabheraId) throw new Error("Create or select a Tabhera before creating a Cell.");
      await request<Unit>(`/api/units/${tabheraId}/children`, {
        method: "POST", body: JSON.stringify({ code: data.get("code"), name: data.get("name") })
      });
      await reload(); form.reset();
      setNotice("Cell created. You can now assign members to it.");
    });
  }

  async function createMember(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      await request<Member>(`/api/nyikas/${nyikaId}/members`, {
        method: "POST", body: JSON.stringify({
          firstName: data.get("firstName"), lastName: data.get("lastName"),
          zccAppNumber: data.get("zccAppNumber"), eliteNumber: data.get("eliteNumber"),
          classificationId: data.get("classificationId"), cellId: data.get("cellId") || null
        })
      });
      await reload(); form.reset(); setMemberCellId("");
      setNotice("Member registered successfully.");
    });
  }

  async function assignSelected() {
    if (!cellId || !selectedIds.length) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const result = await request<{ assignedCount: number }>(`/api/units/${cellId}/members/bulk-assign`, {
        method: "POST", body: JSON.stringify({ memberIds: selectedIds })
      });
      await reload(); setSelectedIds([]);
      setNotice(`${result.assignedCount} member(s) assigned to the selected Cell.`);
    } catch (e) { setError(e instanceof Error ? e.message : "Members could not be assigned."); }
    finally { setBusy(false); }
  }

  function toggleMember(id: string, checked: boolean) {
    setSelectedIds(current => checked ? [...current, id] : current.filter(value => value !== id));
  }

  const selectableVisibleMembers = visibleMembers.filter(member => !member.cellId && member.active);
  const allVisibleSelected = selectableVisibleMembers.length > 0 && selectableVisibleMembers.every(member => selectedIds.includes(member.id));

  return <div className="nyika-workspace">
    <section className="nyika-welcome">
      <div><span className="nyika-eyebrow">NYIKA ADMINISTRATION</span><h2>Your Nyika, organised.</h2><p>Register members, create Cells and keep every member in the right place.</p></div>
      <div className="nyika-context"><span>ACTIVE NYIKA</span><strong>{units.find(unit => unit.id === nyikaId)?.name ?? "Nyika not found"}</strong></div>
    </section>

    {(error || notice) && <div className={error ? "message error" : "message success"} role="status">{error || notice}</div>}

    <section className="nyika-stat-grid">
      <article><span>MEMBERS</span><strong>{members.length}</strong></article>
      <article><span>CELLS</span><strong>{cells.length}</strong></article>
      <article><span>ASSIGNED TO CELLS</span><strong>{assignedCount}</strong></article>
      <article><span>AWAITING CELL</span><strong>{unassignedCount}</strong></article>
    </section>

    <section className="nyika-workflow-grid">
      <article className="nyika-card">
        <div className="nyika-card-heading"><span>01</span><div><h3>Create a Cell</h3><p>Set up the Cell before or after registering members.</p></div></div>
        <form className="form-stack" onSubmit={createCell}>
          <label>Tabhera<select value={tabheraId} onChange={e => setTabheraId(e.target.value)} required><option value="">Select Tabhera</option>{tabheras.map(tab => <option key={tab.id} value={tab.id}>{tab.code} — {tab.name}</option>)}</select></label>
          <label>Cell name<input name="name" required maxLength={160} placeholder="Enter Cell name" /></label>
          <label>Cell code<input name="code" required maxLength={60} placeholder="Enter a unique code" /></label>
          <button className="primary-button" disabled={busy || !tabheraId}>Create Cell</button>
        </form>
        {tabheraId && <div className="nyika-cell-list"><strong>Cells in this Tabhera</strong>{cells.filter(cell => cell.parentId === tabheraId).map(cell => <div key={cell.id}><span>{cell.name}</span><small>{cell.code}</small></div>)}{!cells.some(cell => cell.parentId === tabheraId) && <p>No Cells created in this Tabhera yet.</p>}</div>}
        {!tabheras.length && <p className="nyika-inline-note">No active Tabheras are available. Ask Central Administration to configure the Nyika's Tabheras.</p>}
      </article>

      <article className="nyika-card">
        <div className="nyika-card-heading"><span>02</span><div><h3>Register a member</h3><p>Classification is required. Elite Number and ZCC application number are separate optional details.</p></div></div>
        <form className="form-stack" onSubmit={createMember}>
          <div className="field-pair"><label>First name<input name="firstName" required maxLength={100} /></label><label>Last name<input name="lastName" required maxLength={100} /></label></div>
          <label>Classification<select name="classificationId" required defaultValue=""><option value="" disabled>Select classification</option>{classifications.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          {!classifications.length && <p className="nyika-inline-note">No active classifications are configured. Central Administration must configure classifications before members can be registered.</p>}
          <label>Elite Number <span className="nyika-optional">(optional)</span><input name="eliteNumber" maxLength={80} /></label>
          <label>ZCC application number <span className="nyika-optional">(optional)</span><input name="zccAppNumber" maxLength={80} /></label>
          <label>Assign to Cell now <span className="nyika-optional">(optional)</span><select name="cellId" value={memberCellId} onChange={e => setMemberCellId(e.target.value)}><option value="">Leave unassigned for now</option>{cells.map(cell => <option key={cell.id} value={cell.id}>{cell.code} — {cell.name}</option>)}</select></label>
          <button className="primary-button" disabled={busy || !nyikaId || !classifications.length}>Register member</button>
        </form>
      </article>
    </section>

    <section className="nyika-card nyika-register">
      <div className="nyika-register-heading"><div><span className="nyika-eyebrow">MEMBERSHIP REGISTER</span><h3>Members in your Nyika</h3><p>Search and filter members, or select multiple unassigned members to place them in a Cell.</p></div><span className="nyika-register-count">{visibleMembers.length} MEMBERS</span></div>
      <div className="nyika-register-tools">
        <input aria-label="Search members" value={search} onChange={e => setSearch(e.target.value)} placeholder="Search name, ZCC number or Elite Number" />
        <select aria-label="Filter by classification" value={classificationFilter} onChange={e => setClassificationFilter(e.target.value)}><option value="">All classifications</option>{classifications.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select>
        <select aria-label="Filter by Cell" value={cellFilter} onChange={e => setCellFilter(e.target.value)}><option value="">All Cells</option>{cells.map(cell => <option key={cell.id} value={cell.id}>{cell.code} — {cell.name}</option>)}</select>
        <select aria-label="Destination Cell" value={cellId} onChange={e => setCellId(e.target.value)}><option value="">Choose destination Cell</option>{cells.map(cell => <option key={cell.id} value={cell.id}>{cell.code} — {cell.name}</option>)}</select>
        <button className="primary-button" type="button" disabled={busy || !cellId || !selectedIds.length} onClick={assignSelected}>Assign selected ({selectedIds.length})</button>
      </div>
      <div className="table-wrap"><table><thead><tr><th><input type="checkbox" aria-label="Select all visible unassigned members" checked={allVisibleSelected} onChange={e => setSelectedIds(current => e.target.checked ? [...new Set([...current, ...selectableVisibleMembers.map(member => member.id)])] : current.filter(id => !visibleMembers.some(member => member.id === id)))} /></th><th>MEMBER</th><th>CLASSIFICATION</th><th>ELITE NUMBER</th><th>ZCC NUMBER</th><th>CELL</th><th>STATUS</th></tr></thead><tbody>
        {visibleMembers.map(member => <tr key={member.id}><td><input type="checkbox" aria-label={`Select ${member.firstName} ${member.lastName}`} checked={selectedIds.includes(member.id)} disabled={!!member.cellId || !member.active} onChange={e => toggleMember(member.id, e.target.checked)} /></td><td><strong>{member.lastName}, {member.firstName}</strong></td><td>{member.classificationName || "—"}</td><td>{member.eliteNumber || "—"}</td><td>{member.zccAppNumber || "—"}</td><td>{member.cellName || <span className="nyika-unassigned">Awaiting assignment</span>}</td><td>{member.active ? "Active" : "Inactive"}</td></tr>)}
        {!visibleMembers.length && <tr><td colSpan={7} className="empty-table">No members match these filters.</td></tr>}
      </tbody></table></div>
    </section>

    <section className="nyika-next-step">
      <div><span className="nyika-eyebrow">NEXT IN YOUR WORKSPACE</span><h3>Households and contributions</h3><p>Household grouping, surname suggestions and member contribution capture are the next workflow milestones.</p></div>
      <span className="nyika-next-status">UP NEXT</span>
    </section>
  </div>;
}
