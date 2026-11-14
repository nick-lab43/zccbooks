import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Member = { id: string; cellId: string; zccAppNumber: string | null; firstName: string; lastName: string; active: boolean };
type Position = { id: string; code: string; name: string; description: string | null; allowConcurrent: boolean; active: boolean };
type Appointment = { id: string; positionId: string; positionCode: string; positionName: string; scopeUnitId: string; scopeLevel: string; scopeName: string; validFrom: string; validTo: string | null; note: string | null };

export default function MemberAppointments({ organisationId, authorizationHeader, canConfigurePositions }: {
  organisationId: string; authorizationHeader: string; canConfigurePositions: boolean;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [cellId, setCellId] = useState("");
  const [members, setMembers] = useState<Member[]>([]);
  const [memberId, setMemberId] = useState("");
  const [positions, setPositions] = useState<Position[]>([]);
  const [appointments, setAppointments] = useState<Appointment[]>([]);
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

  async function loadAppointments(id: string) {
    if (!id) { setAppointments([]); return; }
    setAppointments(await request<Appointment[]>(`/api/members/${id}/appointments`));
  }

  useEffect(() => {
    if (!organisationId) { setUnits([]); setCellId(""); return; }
    request<Unit[]>(`/api/organisations/${organisationId}/units`)
      .then(rows => {
        const cells = rows.filter(unit => unit.level === "CELL" && unit.active);
        setUnits(rows);
        setCellId(current => cells.some(cell => cell.id === current) ? current : cells[0]?.id ?? "");
      }).catch((e: Error) => setError(e.message));
    request<Position[]>("/api/appointments/positions")
      .then(setPositions).catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  useEffect(() => {
    setMemberId(""); setMembers([]); setAppointments([]);
    if (!cellId) return;
    request<Member[]>(`/api/units/${cellId}/members`)
      .then(rows => setMembers(rows))
      .catch((e: Error) => setError(e.message));
  }, [cellId]);

  useEffect(() => {
    if (!memberId) { setAppointments([]); return; }
    loadAppointments(memberId).catch((e: Error) => setError(e.message));
  }, [memberId]);

  const ancestorScopes = useMemo(() => {
    const result: Unit[] = [];
    let current = units.find(unit => unit.id === cellId);
    while (current) {
      if (["NYIKA", "TABHERA", "CELL"].includes(current.level) && current.active) result.push(current);
      current = current.parentId ? units.find(unit => unit.id === current?.parentId) : undefined;
    }
    return result;
  }, [units, cellId]);

  async function submit(event: FormEvent<HTMLFormElement>, action: (form: FormData) => Promise<void>) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    try { await action(new FormData(event.currentTarget)); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function createAppointment(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      await request(`/api/members/${memberId}/appointments`, {
        method: "POST",
        body: JSON.stringify({
          positionId: data.get("positionId"),
          scopeUnitId: data.get("scopeUnitId"),
          validFrom: data.get("validFrom"),
          validTo: data.get("validTo") || null,
          note: data.get("note") || null
        })
      });
      await loadAppointments(memberId);
      form.reset();
      setNotice("Appointment recorded. The previous appointment history remains available.");
    });
  }

  async function endAppointment(id: string) {
    setBusy(true); setError(""); setNotice("");
    try {
      const today = new Date();
      const localDate = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
      await request(`/api/appointments/${id}/end`, {
        method: "PATCH",
        body: JSON.stringify({ validTo: localDate })
      });
      await loadAppointments(memberId);
      setNotice("Appointment ended. Its history has been retained.");
    } catch (e) { setError(e instanceof Error ? e.message : "Could not end appointment."); }
    finally { setBusy(false); }
  }

  async function createPosition(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      await request("/api/appointments/positions", {
        method: "POST",
        body: JSON.stringify({
          code: data.get("code"), name: data.get("name"),
          description: data.get("description"),
          allowConcurrent: data.get("allowConcurrent") === "on"
        })
      });
      setPositions(await request<Position[]>("/api/appointments/positions"));
      form.reset();
      setNotice("Church position added to the central catalogue.");
    });
  }

  const selectedCell = units.find(unit => unit.id === cellId);
  const selectedMember = members.find(member => member.id === memberId);

  return <section className="panel">
    <div className="panel-title"><div><span className="panel-index">06</span><h2>Church appointments</h2></div><span className="panel-tag">MEMBER OFFICES</span></div>
    <p className="panel-description">Record church offices and appointments separately from application access roles. Appointments are date-effective and remain in the member's history when ended.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    {!organisationId && <div className="empty-note">Select an organisation first.</div>}
    {organisationId && <>
      <label className="search-field"><span>GEOGRAPHICAL CELL</span>
        <select value={cellId} onChange={e => setCellId(e.target.value)}>
          <option value="">Select Cell</option>
          {units.filter(unit => unit.level === "CELL" && unit.active).map(unit => <option key={unit.id} value={unit.id}>{unit.code} — {unit.name}</option>)}
        </select>
      </label>
      {cellId && <>
        <label className="search-field"><span>MEMBER</span>
          <select value={memberId} onChange={e => setMemberId(e.target.value)}>
            <option value="">Select member</option>
            {members.map(member => <option key={member.id} value={member.id}>{member.lastName}, {member.firstName}{member.zccAppNumber ? ` · ${member.zccAppNumber}` : ""}</option>)}
          </select>
        </label>
        {memberId && <>
          <div className="panel-divider" />
          <div className="register-heading"><div><h3>{selectedMember?.firstName} {selectedMember?.lastName}</h3><p>{selectedCell?.name} · {appointments.length} appointment records</p></div></div>
          <form onSubmit={createAppointment} className="form-stack">
            <label>Church position<select name="positionId" required defaultValue=""><option value="">Select position</option>{positions.map(position => <option key={position.id} value={position.id}>{position.name} ({position.code})</option>)}</select></label>
            <label>Appointment scope<select name="scopeUnitId" required defaultValue={cellId}>{ancestorScopes.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
            <div className="field-pair">
              <label>Effective from<input type="date" name="validFrom" required defaultValue={new Date().toISOString().slice(0, 10)} /></label>
              <label>Effective to (optional)<input type="date" name="validTo" /></label>
            </div>
            <label>Appointment note<textarea name="note" maxLength={1000} rows={2} placeholder="Optional context or appointment reference" /></label>
            <button className="primary-button" disabled={busy || !positions.length}>Record appointment</button>
          </form>
          <div className="panel-divider" />
          <div className="register-heading"><div><h3>Appointment history</h3><p>Current and previous appointments</p></div></div>
          <div className="table-wrap"><table><thead><tr><th>POSITION</th><th>SCOPE</th><th>FROM</th><th>TO</th><th>STATUS</th><th></th></tr></thead><tbody>
            {appointments.map(item => <tr key={item.id}>
              <td><strong>{item.positionName}</strong><small className="table-subtext">{item.positionCode}</small></td>
              <td>{item.scopeLevel}<small className="table-subtext">{item.scopeName}</small></td>
              <td>{item.validFrom}</td><td>{item.validTo || "—"}</td>
              <td><span className={item.validTo ? "inactive-label" : "active-label"}>{item.validTo ? "ENDED" : "CURRENT"}</span></td>
              <td>{!item.validTo && <button type="button" className="secondary-button" disabled={busy} onClick={() => endAppointment(item.id)}>End appointment</button>}</td>
            </tr>)}
            {!appointments.length && <tr><td colSpan={6} className="empty-table">No appointments recorded for this member.</td></tr>}
          </tbody></table></div>
        </>}
      </>}
    </>}
    {canConfigurePositions && <>
      <div className="panel-divider" />
      <details className="workflow-disclosure">
        <summary>Configure church position catalogue</summary>
        <form onSubmit={createPosition} className="form-stack">
          <div className="field-pair"><label>Position code<input name="code" required maxLength={60} pattern="[A-Za-z0-9_-]{2,60}" /></label><label>Position name<input name="name" required maxLength={120} /></label></div>
          <label>Description<input name="description" maxLength={300} /></label>
          <label className="checkbox-label"><input type="checkbox" name="allowConcurrent" /> Allow concurrent appointments at different scopes</label>
          <button className="secondary-button" disabled={busy}>Add church position</button>
        </form>
      </details>
    </>}
    {!positions.length && <div className="empty-note">No active church positions are configured. Central Administration must add the approved position catalogue before appointments can be recorded.</div>}
  </section>;
}
