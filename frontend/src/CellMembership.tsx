import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string };
type Classification = { id: string; code: string; name: string; active: boolean };
type Member = { id: string; cellId: string; zccAppNumber: string | null; firstName: string; lastName: string; classificationId: string | null; classificationName: string | null; active: boolean };
type Household = { id: string; code: string; name: string; householdHeadMemberId: string | null; active: boolean };
type ActiveHouseholdMember = { memberId: string };
type HouseholdMember = { id: string; zccAppNumber: string | null; firstName: string; lastName: string; relationshipLabel: string; validFrom: string; validTo: string | null };

export default function CellMembership({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [classifications, setClassifications] = useState<Classification[]>([]);
  const [cellId, setCellId] = useState("");
  const [members, setMembers] = useState<Member[]>([]);
  const [households, setHouseholds] = useState<Household[]>([]);
  const [householdId, setHouseholdId] = useState("");
  const [householdMembers, setHouseholdMembers] = useState<HouseholdMember[]>([]);
  const [activeHouseholdMembers, setActiveHouseholdMembers] = useState<ActiveHouseholdMember[]>([]);
  const [headCandidates, setHeadCandidates] = useState<Member[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo((): Record<string, string> => {
    if (!authorizationHeader) return {};
    return { Authorization: authorizationHeader };
  }, [authorizationHeader]);

  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, { ...options, headers: { "Content-Type": "application/json", ...headers, ...options?.headers } });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.status === 204 ? undefined as T : response.json() as Promise<T>;
  }

  async function loadCellData(id: string) {
    if (!id) { setMembers([]); setHouseholds([]); setActiveHouseholdMembers([]); return; }
    const [memberRows, householdRows, activeHouseholdRows] = await Promise.all([
      request<Member[]>(`/api/units/${id}/members`),
      request<Household[]>(`/api/units/${id}/households`),
      request<ActiveHouseholdMember[]>(`/api/units/${id}/household-members`)
    ]);
    setMembers(memberRows);
    setHouseholds(householdRows);
    setActiveHouseholdMembers(activeHouseholdRows);
  }

  async function loadHousehold(id: string) {
    if (!id) { setHouseholdMembers([]); setHeadCandidates([]); return; }
    const [rows, candidates] = await Promise.all([
      request<HouseholdMember[]>(`/api/households/${id}/members`),
      request<Member[]>(`/api/households/${id}/head-candidates`)
    ]);
    setHouseholdMembers(rows);
    setHeadCandidates(candidates);
  }

  useEffect(() => {
    setUnits([]); setClassifications([]); setCellId(""); setHouseholdId("");
    if (!organisationId) return;
    request<Unit[]>(`/api/organisations/${organisationId}/units`)
      .then((rows) => {
        setUnits(rows);
        const first = rows.find((unit) => unit.level === "CELL");
        setCellId(first?.id ?? "");
      })
      .catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  useEffect(() => {
    setActiveHouseholdMembers([]);
    if (!cellId) {
      setMembers([]); setHouseholds([]); setClassifications([]);
      setHouseholdId("");
      return;
    }
    Promise.all([
      request<Member[]>(`/api/units/${cellId}/members`),
      request<Household[]>(`/api/units/${cellId}/households`),
      request<Classification[]>(`/api/units/${cellId}/classifications`),
      request<ActiveHouseholdMember[]>(`/api/units/${cellId}/household-members`)
    ]).then(([memberRows, householdRows, categoryRows, activeHouseholdRows]) => {
      setMembers(memberRows); setHouseholds(householdRows); setClassifications(categoryRows);
      setActiveHouseholdMembers(activeHouseholdRows);
      setHouseholdId((current) => householdRows.some((h) => h.id === current) ? current : householdRows[0]?.id ?? "");
    }).catch((e: Error) => {
      setActiveHouseholdMembers([]);
      setError(e.message);
    });
  }, [cellId]);

  useEffect(() => { loadHousehold(householdId).catch((e: Error) => setError(e.message)); }, [householdId]);

  async function submit(event: FormEvent<HTMLFormElement>, action: (form: FormData) => Promise<void>) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    try { await action(new FormData(event.currentTarget)); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function createMember(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async (data) => {
      await request(`/api/units/${cellId}/members`, { method: "POST", body: JSON.stringify({
        zccAppNumber: data.get("zccAppNumber"), firstName: data.get("firstName"),
        lastName: data.get("lastName"), classificationId: data.get("classificationId") || null
      }) });
      await loadCellData(cellId); form.reset(); setNotice("Member registered in the selected Cell.");
    });
  }

  async function createHousehold(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async (data) => {
      await request(`/api/units/${cellId}/households`, { method: "POST", body: JSON.stringify({ code: data.get("code"), name: data.get("name") }) });
      const rows = await request<Household[]>(`/api/units/${cellId}/households`);
      setHouseholds(rows); setHouseholdId(rows[rows.length - 1]?.id ?? ""); form.reset();
      setNotice("Household created.");
    });
  }

  async function addHouseholdMember(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async (data) => {
      await request(`/api/households/${householdId}/members`, { method: "POST", body: JSON.stringify({
        memberId: data.get("memberId"), relationshipLabel: data.get("relationshipLabel")
      }) });
      await loadHousehold(householdId); form.reset(); setNotice("Member added to household.");
    });
  }

  async function setHead(event: FormEvent<HTMLFormElement>) {
    await submit(event, async (data) => {
      await request(`/api/households/${householdId}/head`, { method: "PUT", body: JSON.stringify({ memberId: data.get("memberId") || null }) });
      await loadCellData(cellId); setNotice("Household head updated.");
    });
  }

  const selectedCell = units.find((unit) => unit.id === cellId);
  const selectedHousehold = households.find((item) => item.id === householdId);
  const activeHouseholdMemberIds = new Set(activeHouseholdMembers.map((item) => item.memberId));
  const availableMembers = members.filter((member) => member.active && !activeHouseholdMemberIds.has(member.id));

  return (
    <section className="panel cell-membership-panel">
      <div className="panel-title"><div><span className="panel-index">03</span><h2>Cell membership and household register</h2></div><span className="panel-tag">SCOPED RECORDS</span></div>
      <p className="panel-description">Register members directly within an authorised Cell. Household membership is maintained separately and retains its effective-date history.</p>
      {!organisationId && <div className="empty-note">Select an organisation first.</div>}
      {organisationId && <>
        <label className="search-field"><span>GEOGRAPHICAL CELL</span>
          <select value={cellId} onChange={(e) => setCellId(e.target.value)}>
            <option value="">Select Cell</option>
            {units.filter((unit) => unit.level === "CELL").map((unit) => <option key={unit.id} value={unit.id}>{unit.code} — {unit.name}</option>)}
          </select>
        </label>
        {!units.some((unit) => unit.level === "CELL") && <div className="empty-note">No Cells are available within this administrator's assigned scope.</div>}
        {cellId && <>
          <div className="panel-divider" />
          <div className="panel-title"><div><span className="panel-index">04</span><h2>Register member</h2></div><span className="panel-tag">{selectedCell?.name}</span></div>
          <form onSubmit={createMember} className="form-stack">
            <label>ZCC app number<input name="zccAppNumber" maxLength={80} placeholder="Existing ZCC app number" /></label>
            <div className="field-pair"><label>First name<input name="firstName" required maxLength={100} /></label><label>Last name<input name="lastName" required maxLength={100} /></label></div>
            <label>Member classification<select name="classificationId" defaultValue=""><option value="">Unassigned</option>{classifications.map((item) => <option key={item.id} value={item.id}>{item.name} ({item.code})</option>)}</select></label>
            <button className="primary-button" disabled={busy}>{busy ? "SAVING..." : "REGISTER IN CELL"}</button>
          </form>
          <div className="panel-divider" />
          <div className="register-heading"><div><h2>Cell member register</h2><p>{selectedCell?.code} · {selectedCell?.name}</p></div><span className="count-box">{members.length} RECORDS</span></div>
          <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>ZCC APP NO.</th><th>CLASSIFICATION</th><th>STATUS</th></tr></thead><tbody>
            {members.map((member) => <tr key={member.id}><td><strong>{member.lastName}, {member.firstName}</strong></td><td>{member.zccAppNumber || "—"}</td><td>{member.classificationName || "Unassigned"}</td><td><span className="active-label">{member.active ? "ACTIVE" : "INACTIVE"}</span></td></tr>)}
            {!members.length && <tr><td colSpan={4} className="empty-table">No members registered in this Cell.</td></tr>}
          </tbody></table></div>
          <div className="panel-divider" />
          <div className="panel-title"><div><span className="panel-index">05</span><h2>Household register</h2></div><span className="panel-tag">{households.length} HOUSEHOLDS</span></div>
          <form onSubmit={createHousehold} className="form-stack">
            <div className="field-pair"><label>Household code<input name="code" required maxLength={60} /></label><label>Family designation<input name="name" required maxLength={160} /></label></div>
            <button className="secondary-button" disabled={busy}>Create household</button>
          </form>
          <div className="table-wrap"><table><thead><tr><th>CODE</th><th>HOUSEHOLD / FAMILY</th><th>STATUS</th></tr></thead><tbody>
            {households.map((item) => <tr key={item.id}><td><strong>{item.code}</strong></td><td>{item.name}</td><td>{item.active ? "ACTIVE" : "INACTIVE"}</td></tr>)}
            {!households.length && <tr><td colSpan={3} className="empty-table">No households in this Cell.</td></tr>}
          </tbody></table></div>
          {households.length > 0 && <>
            <div className="panel-divider" />
            <label className="search-field"><span>SELECT HOUSEHOLD</span><select value={householdId} onChange={(e) => setHouseholdId(e.target.value)}>{households.map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}</select></label>
            <div className="panel-title"><div><span className="panel-index">06</span><h2>Household membership</h2></div><span className="panel-tag">{householdMembers.length} HISTORY ROWS</span></div>
            <form onSubmit={addHouseholdMember} className="form-stack">
              <label>Member<select name="memberId" required defaultValue=""><option value="">Select member</option>{availableMembers.map((item) => <option key={item.id} value={item.id}>{item.lastName}, {item.firstName} · {item.zccAppNumber || "No app number"}</option>)}</select></label>
              <label>Relationship label<input name="relationshipLabel" required maxLength={80} placeholder="Configured relationship label" /></label>
              <button className="secondary-button" disabled={busy || !availableMembers.length}>Add household member</button>
            </form>
            <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>RELATIONSHIP</th><th>FROM</th><th>TO</th></tr></thead><tbody>
              {householdMembers.map((item, index) => <tr key={`${item.id}-${index}`}><td><strong>{item.lastName}, {item.firstName}</strong></td><td>{item.relationshipLabel}</td><td>{item.validFrom}</td><td>{item.validTo || "CURRENT"}</td></tr>)}
              {!householdMembers.length && <tr><td colSpan={4} className="empty-table">No household members recorded.</td></tr>}
            </tbody></table></div>
            <form onSubmit={setHead} className="form-stack">
              <label>Household head<select name="memberId" defaultValue={selectedHousehold?.householdHeadMemberId ?? ""}><option value="">Not designated</option>{headCandidates.map((item) => <option key={item.id} value={item.id}>{item.lastName}, {item.firstName}</option>)}</select></label>
              <button className="secondary-button" disabled={busy}>Save household head</button>
            </form>
          </>}
        </>}
      </>}
      {(error || notice) && <div className={error ? "message error" : "message success"} role="status">{error || notice}</div>}
    </section>
  );
}
