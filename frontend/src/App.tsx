import { useEffect, useState } from "react";
import type { FormEvent } from "react";

type Organisation = { id: string; name: string; createdAt: string };
type Branch = { id: string; organisationId: string; code: string; name: string; districtName?: string; countryCode?: string };
type Classification = { id: string; code: string; name: string; active: boolean };
type Member = { id: string; branchId: string; zccAppNumber?: string; firstName: string; lastName: string; classificationId?: string; classificationName?: string; active: boolean };

async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...options, headers: { "Content-Type": "application/json", ...options?.headers } });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail ?? `Request failed (${response.status})`);
  }
  return response.status === 204 ? (undefined as T) : response.json() as Promise<T>;
}

export default function App() {
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [organisationId, setOrganisationId] = useState("");
  const [branches, setBranches] = useState<Branch[]>([]);
  const [branchId, setBranchId] = useState("");
  const [classifications, setClassifications] = useState<Classification[]>([]);
  const [members, setMembers] = useState<Member[]>([]);
  const [search, setSearch] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);

  async function refreshOrganisations() {
    const rows = await api<Organisation[]>("/api/organisations");
    setOrganisations(rows);
    if (!organisationId && rows.length) setOrganisationId(rows[0].id);
  }

  useEffect(() => { refreshOrganisations().catch((e: Error) => setError(e.message)); }, []);

  useEffect(() => {
    if (!organisationId) { setBranches([]); setClassifications([]); setBranchId(""); return; }
    Promise.all([
      api<Branch[]>(`/api/organisations/${organisationId}/branches`),
      api<Classification[]>(`/api/organisations/${organisationId}/classifications`),
    ]).then(([branchRows, classificationRows]) => {
      setBranches(branchRows);
      setClassifications(classificationRows);
      setBranchId((current) => branchRows.some((b) => b.id === current) ? current : branchRows[0]?.id ?? "");
    }).catch((e: Error) => setError(e.message));
  }, [organisationId]);

  useEffect(() => {
    if (!branchId) { setMembers([]); return; }
    const params = new URLSearchParams();
    if (search.trim()) params.set("search", search.trim());
    api<Member[]>(`/api/branches/${branchId}/members?${params.toString()}`)
      .then(setMembers).catch((e: Error) => setError(e.message));
  }, [branchId, search]);

  async function submit(event: FormEvent<HTMLFormElement>, action: () => Promise<void>) {
    event.preventDefault(); setError(""); setNotice(""); setBusy(true);
    try { await action(); }
    catch (e) { setError(e instanceof Error ? e.message : "The operation could not be completed."); }
    finally { setBusy(false); }
  }

  async function createOrganisation(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await submit(event, async () => {
      const created = await api<Organisation>("/api/organisations", { method: "POST", body: JSON.stringify({ name: form.get("name") }) });
      const rows = await api<Organisation[]>("/api/organisations");
      setOrganisations(rows); setOrganisationId(created.id); setNotice("Organisation created."); formElement.reset();
    });
  }

  async function createBranch(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await submit(event, async () => {
      const created = await api<Branch>(`/api/organisations/${organisationId}/branches`, { method: "POST", body: JSON.stringify({
        code: form.get("code"), name: form.get("name"), districtName: form.get("districtName"), countryCode: form.get("countryCode") || null,
      }) });
      const rows = await api<Branch[]>(`/api/organisations/${organisationId}/branches`);
      setBranches(rows); setBranchId(created.id); setNotice("Branch created."); formElement.reset();
    });
  }

  async function createClassification(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await submit(event, async () => {
      await api(`/api/organisations/${organisationId}/classifications`, { method: "POST", body: JSON.stringify({ code: form.get("code"), name: form.get("name") }) });
      setClassifications(await api<Classification[]>(`/api/organisations/${organisationId}/classifications`));
      setNotice("Member classification created."); formElement.reset();
    });
  }

  async function createMember(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await submit(event, async () => {
      await api(`/api/branches/${branchId}/members`, { method: "POST", body: JSON.stringify({
        zccAppNumber: form.get("zccAppNumber"), firstName: form.get("firstName"), lastName: form.get("lastName"),
        classificationId: form.get("classificationId") || null,
      }) });
      setMembers(await api<Member[]>(`/api/branches/${branchId}/members`)); setNotice("Member registered in this branch."); formElement.reset();
    });
  }

  const selectedBranch = branches.find((item) => item.id === branchId);

  return (
    <main className="system">
      <header className="masthead">
        <div className="masthead-brand"><span className="brand-mark">Z</span><div><strong>ZCC BOOKS</strong><small>CHURCH FINANCE &amp; RECORDS</small></div></div>
        <div className="masthead-right"><span>BRANCH ADMINISTRATION</span><span className="system-state">DEVELOPMENT SYSTEM</span></div>
      </header>
      <nav className="menubar"><span>Registry</span><span>Branches</span><span>Members</span><span>Configuration</span><span className="menu-spacer" /><span>Milestone 02</span></nav>
      <section className="workspace-heading">
        <div><div className="breadcrumb">SYSTEM / ORGANISATION / MEMBERSHIP REGISTER</div><h1>Branch &amp; Member Register</h1><p>Establish branch records and maintain the membership register.</p></div>
        <div className="record-stamp"><span>MODULE</span><strong>REG-02</strong><span>FOUNDATION RELEASE</span></div>
      </section>
      {(error || notice) && <div className={error ? "message error" : "message success"} role="status">{error || notice}<button onClick={() => { setError(""); setNotice(""); }} aria-label="Dismiss message">×</button></div>}
      <section className="selection-bar">
        <label>Organisation<select value={organisationId} onChange={(e) => setOrganisationId(e.target.value)}><option value="">Select organisation</option>{organisations.map((org) => <option key={org.id} value={org.id}>{org.name}</option>)}</select></label>
        <label>Branch<select value={branchId} onChange={(e) => setBranchId(e.target.value)} disabled={!branches.length}><option value="">Select branch</option>{branches.map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}</select></label>
        <div className="selection-summary"><span>REGISTERED MEMBERS</span><strong>{members.length.toString().padStart(3, "0")}</strong></div>
      </section>
      <div className="content-grid">
        <section className="panel">
          <div className="panel-title"><div><span className="panel-index">01</span><h2>Organisation setup</h2></div><span className="panel-tag">CENTRAL RECORD</span></div>
          <form onSubmit={createOrganisation} className="form-stack">
            <label>Organisation name<input name="name" required maxLength={160} placeholder="e.g. ZCC Demonstration Organisation" /></label>
            <button className="primary-button" disabled={busy}>Create organisation</button>
          </form>
          <div className="panel-divider" />
          <div className="panel-title"><div><span className="panel-index">02</span><h2>Register branch</h2></div></div>
          <form onSubmit={createBranch} className="form-stack">
            <div className="field-pair"><label>Branch code<input name="code" required maxLength={40} placeholder="e.g. POL-001" /></label><label>Country code<input name="countryCode" maxLength={2} pattern="[A-Z]{2}" placeholder="ZA" /></label></div>
            <label>Branch name<input name="name" required maxLength={160} placeholder="Branch name" /></label>
            <label>District / region<input name="districtName" maxLength={160} placeholder="District or region" /></label>
            <button className="primary-button" disabled={busy || !organisationId}>Register branch</button>
          </form>
          <div className="panel-divider" />
          <div className="panel-title"><div><span className="panel-index">03</span><h2>Member classifications</h2></div><span className="panel-tag">CONFIGURABLE</span></div>
          <form onSubmit={createClassification} className="form-stack">
            <div className="field-pair"><label>Classification code<input name="code" required maxLength={60} placeholder="e.g. SUNGANO" /></label><label>Display name<input name="name" required maxLength={120} placeholder="Classification name" /></label></div>
            <button className="secondary-button" disabled={busy || !organisationId}>Add classification</button>
          </form>
          <div className="classification-list">
            {classifications.map((item) => <div className="classification-row" key={item.id}><span className="classification-code">{item.code}</span><span>{item.name}</span><span className="active-label">ACTIVE</span></div>)}
            {!classifications.length && <p className="empty-note">No classifications configured for this organisation.</p>}
          </div>
        </section>
        <section className="panel member-panel">
          <div className="panel-title"><div><span className="panel-index">04</span><h2>Register member</h2></div><span className="panel-tag">BRANCH RECORD</span></div>
          <p className="panel-description">Members are registered against the selected branch. The ZCC app number is optional for demonstration records, but must be unique when supplied.</p>
          <form onSubmit={createMember} className="form-stack">
            <label>ZCC app number<input name="zccAppNumber" maxLength={80} placeholder="Enter member app number" /></label>
            <div className="field-pair"><label>First name<input name="firstName" required maxLength={100} placeholder="First name" /></label><label>Last name<input name="lastName" required maxLength={100} placeholder="Last name" /></label></div>
            <label>Member classification<select name="classificationId" defaultValue=""><option value="">Unassigned</option>{classifications.filter((item) => item.active).map((item) => <option key={item.id} value={item.id}>{item.name} ({item.code})</option>)}</select></label>
            <button className="primary-button" disabled={busy || !branchId}>Add member to branch</button>
          </form>
          <div className="panel-divider" />
          <div className="register-heading"><div><h2>Membership register</h2><p>{selectedBranch ? `${selectedBranch.name}${selectedBranch.districtName ? " · " + selectedBranch.districtName : ""}` : "Select a branch to view its register"}</p></div><span className="count-box">{members.length} RECORDS</span></div>
          <label className="search-field"><span>SEARCH REGISTER</span><input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Name or ZCC app number" disabled={!branchId} /></label>
          <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>ZCC APP NO.</th><th>CLASSIFICATION</th><th>STATUS</th></tr></thead><tbody>
            {members.map((member) => <tr key={member.id}><td><strong>{member.lastName}, {member.firstName}</strong></td><td>{member.zccAppNumber || "—"}</td><td>{member.classificationName || "Unassigned"}</td><td><span className="active-label">ACTIVE</span></td></tr>)}
            {!members.length && <tr><td colSpan={4} className="empty-table">No member records found for this branch.</td></tr>}
          </tbody></table></div>
        </section>
      </div>
      <footer className="system-footer"><span>ZCC BOOKS · REGISTRY MODULE</span><span>DEMONSTRATION DATA ONLY · NOT CONNECTED TO THE LIVE ZCC APP</span></footer>
    </main>
  );
}
