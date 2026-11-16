import { useEffect, useMemo, useState } from "react";

type Unit = { id: string; level: string; code: string; name: string };
type Member = { firstName: string; lastName: string; zccAppNumber: string | null; active: boolean; registeredOn: string; classification: string; cellCode: string; cellName: string };
type Appointment = { firstName: string; lastName: string; zccAppNumber: string | null; positionCode: string; positionName: string; validFrom: string; validTo: string | null; appointmentLevel: string; appointmentScope: string; note: string | null };

export default function MembershipReports({ organisationId, authorizationHeader }: {
  organisationId: string; authorizationHeader: string;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [scopeUnitId, setScopeUnitId] = useState("");
  const [from, setFrom] = useState(new Date(new Date().getFullYear(), 0, 1).toISOString().slice(0, 10));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [type, setType] = useState("MEMBER_REGISTER");
  const [members, setMembers] = useState<Member[]>([]);
  const [appointments, setAppointments] = useState<Appointment[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, { headers });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.json() as Promise<T>;
  }

  useEffect(() => {
    if (!organisationId) { setUnits([]); setScopeUnitId(""); return; }
    request<Unit[]>(`/api/organisations/${organisationId}/membership-reports/units`)
      .then(rows => {
        setUnits(rows);
        setScopeUnitId(current => rows.some(row => row.id === current) ? current : rows[0]?.id ?? "");
      }).catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  function query() {
    return new URLSearchParams({ scopeUnitId, from, to }).toString();
  }

  async function preview() {
    if (!scopeUnitId || !from || !to || from > to) {
      setError("Select a geographical scope and a valid date range."); return;
    }
    setBusy(true); setError(""); setMembers([]); setAppointments([]);
    try {
      const params = query();
      if (type === "MEMBER_REGISTER")
        setMembers(await request<Member[]>(`/api/organisations/${organisationId}/membership-reports/members?${params}`));
      else
        setAppointments(await request<Appointment[]>(`/api/organisations/${organisationId}/membership-reports/appointments?${params}`));
    } catch (e) { setError(e instanceof Error ? e.message : "Report could not be loaded."); }
    finally { setBusy(false); }
  }

  async function download() {
    if (!scopeUnitId || !from || !to || from > to) {
      setError("Select a geographical scope and a valid date range."); return;
    }
    setBusy(true); setError("");
    try {
      const response = await fetch(`${apiBase}/api/organisations/${organisationId}/membership-reports/export?${query()}&type=${type}`, { headers });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Export failed (${response.status}).`);
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `zcc-${type.toLowerCase().replace(/_/g, "-")}-${from}-to-${to}.docx`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (e) { setError(e instanceof Error ? e.message : "Word export failed."); }
    finally { setBusy(false); }
  }

  return <div className="financial-reports">
    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">01</span><h2>Membership report parameters</h2></div><span className="panel-tag">AUTHORISATION SCOPED</span></div>
      <p className="panel-description">Reports include Cell-based members or appointments assigned to the selected geographical unit and its descendants. Access is checked by the API for every request. The date range filters member registration dates or appointment effective periods.</p>
      <div className="form-stack">
        <label>Report<select value={type} onChange={e => setType(e.target.value)}>
          <option value="MEMBER_REGISTER">Membership register</option>
          <option value="APPOINTMENT_REGISTER">Church appointments register</option>
        </select></label>
        <label>Geographical scope<select value={scopeUnitId} onChange={e => setScopeUnitId(e.target.value)}><option value="">Select scope</option>{units.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
        <div className="field-pair"><label>From<input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label><label>To<input type="date" value={to} onChange={e => setTo(e.target.value)} /></label></div>
        {error && <div className="message error" role="alert">{error}</div>}
        <div className="field-pair">
          <button className="secondary-button" disabled={busy || !scopeUnitId} onClick={preview}>{busy ? "Working…" : "Preview report"}</button>
          <button className="primary-button" disabled={busy || !scopeUnitId} onClick={download}>Download Word report</button>
        </div>
      </div>
    </section>
    {type === "MEMBER_REGISTER" && <section className="panel">
      <div className="panel-title"><div><span className="panel-index">02</span><h2>Membership register</h2></div><span className="panel-tag">{members.length} RECORDS</span></div>
      <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>APP NUMBER</th><th>CLASSIFICATION</th><th>CELL</th><th>REGISTERED</th><th>STATUS</th></tr></thead><tbody>
        {members.map((item, i) => <tr key={i}><td><strong>{item.lastName}, {item.firstName}</strong></td><td>{item.zccAppNumber || "—"}</td><td>{item.classification}</td><td>{item.cellCode} · {item.cellName}</td><td>{item.registeredOn}</td><td>{item.active ? "ACTIVE" : "INACTIVE"}</td></tr>)}
        {!members.length && <tr><td colSpan={6} className="empty-table">Preview the report to load members registered in this period.</td></tr>}
      </tbody></table></div>
    </section>}
    {type === "APPOINTMENT_REGISTER" && <section className="panel">
      <div className="panel-title"><div><span className="panel-index">02</span><h2>Church appointments register</h2></div><span className="panel-tag">{appointments.length} RECORDS</span></div>
      <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>APP NUMBER</th><th>POSITION</th><th>SCOPE</th><th>FROM</th><th>TO</th></tr></thead><tbody>
        {appointments.map((item, i) => <tr key={i}><td><strong>{item.lastName}, {item.firstName}</strong></td><td>{item.zccAppNumber || "—"}</td><td>{item.positionName}<small className="table-subtext">{item.positionCode}</small></td><td>{item.appointmentLevel} · {item.appointmentScope}</td><td>{item.validFrom}</td><td>{item.validTo || "Ongoing"}</td></tr>)}
        {!appointments.length && <tr><td colSpan={6} className="empty-table">Preview the report to load appointments effective during this period.</td></tr>}
      </tbody></table></div>
    </section>}
  </div>;
}
