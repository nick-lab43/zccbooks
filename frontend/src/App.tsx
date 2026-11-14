import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import OrganisationalHierarchy from "./OrganisationalHierarchy";
import CellMembership from "./CellMembership";
import MemberAppointments from "./MemberAppointments";
import FinancialAdministration from "./FinancialAdministration";

type Module = "overview" | "organisation" | "membership" | "appointments" | "financial" | "access";
type CurrentAdmin = { username: string; displayName: string; permissions: string[] };
type AdminUser = { id: string; username: string; displayName: string; email?: string | null; enabled: boolean; createdAt: string | null; assignments: string };
type AdminRole = { id: string; code: string; name: string; description?: string; systemRole: boolean; permissions: string[] };
type Permission = { code: string; name: string; description: string };
type UnitOption = { id: string; level: string; code: string; name: string };
type Invitation = { id: string; username: string; displayName: string; email: string; nyikaCode: string; nyikaName: string; expiresAt: string; status: string };
type AuditEvent = { id: string; actor: string; action: string; targetType: string; targetId: string | null; details: string; occurredAt: string };
type Organisation = { id: string; name: string; createdAt: string };
type Branch = { id: string; organisationId: string; code: string; name: string; districtName?: string; countryCode?: string };
type Classification = { id: string; code: string; name: string; active: boolean };
type Member = { id: string; branchId: string; zccAppNumber?: string; firstName: string; lastName: string; classificationId?: string; classificationName?: string; active: boolean };

let currentAuthHeader: string | null = null;

async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}${path}`, { ...options, headers: { "Content-Type": "application/json", ...(currentAuthHeader ? { Authorization: currentAuthHeader } : {}), ...options?.headers } });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail ?? `Request failed (${response.status})`);
  }
  return response.status === 204 ? (undefined as T) : response.json() as Promise<T>;
}

function MainApp() {
  const [activeModule, setActiveModule] = useState<Module>("overview");
  const [authenticated, setAuthenticated] = useState(false);
  const [currentAdmin, setCurrentAdmin] = useState<CurrentAdmin | null>(null);
  const [loginError, setLoginError] = useState("");
  const [adminUsers, setAdminUsers] = useState<AdminUser[]>([]);
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [adminRoles, setAdminRoles] = useState<AdminRole[]>([]);
  const [permissionOptions, setPermissionOptions] = useState<Permission[]>([]);
  const [accessUnits, setAccessUnits] = useState<UnitOption[]>([]);
  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([]);
  const [accessOrganisationId, setAccessOrganisationId] = useState("");
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [organisationId, setOrganisationId] = useState("");
  const [branches, setBranches] = useState<Branch[]>([]);
  const [branchId, setBranchId] = useState("");
  const [classifications, setClassifications] = useState<Classification[]>([]);
  const [members, setMembers] = useState<Member[]>([]);
  const [unitCount, setUnitCount] = useState(0);
  const [search, setSearch] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);

  async function refreshOrganisations() {
    const rows = await api<Organisation[]>("/api/organisations");
    setOrganisations(rows);
    if (!organisationId && rows.length) setOrganisationId(rows[0].id);
  }

  useEffect(() => { if (authenticated) refreshOrganisations().catch((e: Error) => setError(e.message)); }, [authenticated]);

  useEffect(() => {
    if (!authenticated || activeModule !== "access") return;
    Promise.all([
      api<AdminUser[]>("/api/admin/users"),
      api<AdminRole[]>("/api/admin/roles"),
      api<Permission[]>("/api/admin/permissions"),
      api<AuditEvent[]>("/api/admin/audit"),
      api<Invitation[]>("/api/admin/nyika-invitations"),
    ]).then(([users, roles, permissions, events, inviteRows]) => { setAdminUsers(users); setAdminRoles(roles); setPermissionOptions(permissions); setAuditEvents(events); setInvitations(inviteRows); })
      .catch((e: Error) => setError(e.message));
  }, [authenticated, activeModule]);

  useEffect(() => {
    if (!accessOrganisationId) { setAccessUnits([]); return; }
    api<UnitOption[]>(`/api/organisations/${accessOrganisationId}/units`)
      .then(setAccessUnits).catch((e: Error) => setError(e.message));
  }, [accessOrganisationId]);

  useEffect(() => {
    if (!organisationId) {
      setBranches([]); setClassifications([]); setBranchId(""); setUnitCount(0);
      return;
    }
    Promise.allSettled([
      api<Branch[]>(`/api/organisations/${organisationId}/branches`),
      api<Classification[]>(`/api/organisations/${organisationId}/classifications`),
      api<unknown[]>(`/api/organisations/${organisationId}/units`),
    ]).then(([branchResult, classificationResult, unitResult]) => {
      const branchRows = branchResult.status === "fulfilled" ? branchResult.value : [];
      const classificationRows = classificationResult.status === "fulfilled" ? classificationResult.value : [];
      if (unitResult.status === "rejected") throw unitResult.reason;
      setBranches(branchRows);
      setClassifications(classificationRows);
      setUnitCount(unitResult.value.length);
      setBranchId((current) => branchRows.some((b) => b.id === current) ? current : branchRows[0]?.id ?? "");
      if (branchResult.status === "rejected" && classificationResult.status === "rejected") {
        setNotice("Organisation access is limited to your assigned geographical scope. Branch-based membership maintenance is not available to this account.");
      }
    }).catch((e: Error) => setError(e.message));
  }, [organisationId]);

  useEffect(() => {
    if (!branchId) { setMembers([]); return; }
    const params = new URLSearchParams();
    if (search.trim()) params.set("search", search.trim());
    api<Member[]>(`/api/branches/${branchId}/members?${params.toString()}`)
      .then(setMembers).catch((e: Error) => setError(e.message));
  }, [branchId, search]);

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setLoginError("");
    const form = new FormData(event.currentTarget);
    const username = String(form.get("username") ?? "").trim();
    const password = String(form.get("password") ?? "");
    currentAuthHeader = `Basic ${btoa(`${username}:${password}`)}`;
    try {
      const profile = await api<CurrentAdmin>("/api/auth/me");
      setCurrentAdmin(profile); setAuthenticated(true); setActiveModule("overview");
    } catch (e) {
      currentAuthHeader = null;
      setLoginError(e instanceof Error ? e.message : "Sign-in failed.");
    }
  }

  function logout() {
    currentAuthHeader = null; setAuthenticated(false); setCurrentAdmin(null);
    setAdminUsers([]); setAdminRoles([]); setOrganisations([]); setBranches([]); setMembers([]); setAccessOrganisationId(""); setAccessUnits([]); setAuditEvents([]); setActiveModule("overview");
    setOrganisationId(""); setBranchId(""); setError(""); setNotice("");
  }

  async function createNyikaInvitation(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await submit(event, async () => {
      const result = await api<{ username: string; status: string; expiresIn: string }>("/api/admin/nyika-invitations", {
        method: "POST",
        body: JSON.stringify({ nyikaId: form.get("nyikaId"), displayName: form.get("displayName"), email: form.get("email") }),
      });
      setInvitations(await api<Invitation[]>("/api/admin/nyika-invitations"));
      setNotice(`Invitation emailed. Assigned username: ${result.username}. Link expires in ${result.expiresIn}.`);
      formElement.reset();
    });
  }

  async function revokeNyikaInvitation(id: string) {
    setBusy(true); setError(""); setNotice("");
    try {
      await api(`/api/admin/nyika-invitations/${id}/revoke`, { method: "POST" });
      setInvitations(await api<Invitation[]>("/api/admin/nyika-invitations"));
      setNotice("Invitation revoked.");
    } catch (e) { setError(e instanceof Error ? e.message : "Could not revoke invitation."); }
    finally { setBusy(false); }
  }

  async function createAdminUser(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget; const form = new FormData(formElement);
    await submit(event, async () => {
      await api("/api/admin/users", { method: "POST", body: JSON.stringify({ username: form.get("username"), displayName: form.get("displayName"), password: form.get("password") }) });
      setAdminUsers(await api<AdminUser[]>("/api/admin/users")); setNotice("Administrator account created."); formElement.reset();
    });
  }

  async function assignAdminRole(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget; const form = new FormData(formElement);
    await submit(event, async () => {
      const scopeType = String(form.get("scopeType"));
      await api(`/api/admin/users/${form.get("userId")}/assignments`, { method: "POST", body: JSON.stringify({
        roleId: form.get("roleId"), scopeType,
        organisationId: scopeType === "GLOBAL" ? null : (form.get("organisationId") || null),
        scopeUnitId: scopeType === "UNIT" ? (form.get("scopeUnitId") || null) : null,
      }) });
      setAdminUsers(await api<AdminUser[]>("/api/admin/users")); setNotice("Role assigned.");
    });
  }

  async function createAdminRole(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget; const form = new FormData(formElement);
    await submit(event, async () => {
      await api("/api/admin/roles", { method: "POST", body: JSON.stringify({ code: form.get("code"), name: form.get("name"), description: form.get("description") }) });
      setAdminRoles(await api<AdminRole[]>("/api/admin/roles")); setNotice("Custom role created. Configure its permissions before assigning it."); formElement.reset();
    });
  }

  async function saveRolePermissions(event: FormEvent<HTMLFormElement>) {
    const formElement = event.currentTarget; const form = new FormData(formElement);
    await submit(event, async () => {
      const roleId = String(form.get("roleId"));
      const permissionCodes = form.getAll("permissionCodes").map(String);
      await api(`/api/admin/roles/${roleId}/permissions`, { method: "PUT", body: JSON.stringify({ permissionCodes }) });
      setAdminRoles(await api<AdminRole[]>("/api/admin/roles")); setNotice("Role permissions updated.");
    });
  }

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


  const selectedBranch = branches.find((item) => item.id === branchId);
  const selectedOrganisation = organisations.find((item) => item.id === organisationId);

  if (!authenticated) return (
    <main className="system login-shell">
      <header className="masthead"><div className="masthead-brand"><img className="brand-logo" src="/zcc-star-logo.png" alt="Zion Christian Church star" /><div><strong>ZCC BOOKS</strong><small>CHURCH FINANCE &amp; RECORDS</small></div></div><div className="masthead-right"><span>SECURE ADMINISTRATION</span></div></header>
      <section className="login-panel"><div className="login-kicker">AUTHORISED PERSONNEL ONLY</div><h1>Administrator sign-in</h1><p>Sign in with the administrator account provisioned for this ZCC Books environment.</p>
        {loginError && <div className="message error">{loginError}</div>}
        <form onSubmit={login} className="form-stack"><label>Username<input name="username" autoComplete="username" required /></label><label>Password<input name="password" type="password" autoComplete="current-password" required /></label><button className="primary-button">Sign in</button></form>
        <div className="login-footnote">Access is controlled by administrator roles and assigned geographical scope.</div>
      </section>
    </main>
  );

  return (
    <main className="system">
      <header className="masthead">
        <div className="masthead-brand"><img className="brand-logo" src="/zcc-star-logo.png" alt="Zion Christian Church star" /><div><strong>ZCC BOOKS</strong><small>CHURCH FINANCE &amp; RECORDS</small></div></div>
        <div className="masthead-right"><span>{currentAdmin?.displayName ?? "ADMINISTRATOR"}</span><button className="logout-button" onClick={logout}>SIGN OUT</button><span className="system-state">SECURE SESSION</span></div>
      </header>
      <div className="app-frame">
        <aside className="app-sidebar" aria-label="Application navigation">
          <div className="sidebar-section-label">WORKSPACE</div>
          <button className={activeModule === "overview" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("overview")} aria-current={activeModule === "overview" ? "page" : undefined}><span className="sidebar-icon">OV</span><span>Overview</span></button>
          <div className="sidebar-section-label">GENERAL ADMINISTRATION</div>
          {currentAdmin?.permissions.includes("ORG_VIEW") && <button className={activeModule === "organisation" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("organisation")} aria-current={activeModule === "organisation" ? "page" : undefined}><span className="sidebar-icon">OR</span><span>Organisational structure</span></button>}
          {currentAdmin?.permissions.includes("MEMBERS_VIEW") && <>
            <button className={activeModule === "membership" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("membership")} aria-current={activeModule === "membership" ? "page" : undefined}><span className="sidebar-icon">ME</span><span>Membership</span></button>
            <button className={activeModule === "appointments" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("appointments")} aria-current={activeModule === "appointments" ? "page" : undefined}><span className="sidebar-icon">AP</span><span>Church appointments</span></button>
          </>}
          {currentAdmin?.permissions.includes("FINANCE_VIEW") && <>
            <div className="sidebar-section-label">FINANCIAL ADMINISTRATION</div>
            <button className={activeModule === "financial" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("financial")} aria-current={activeModule === "financial" ? "page" : undefined}><span className="sidebar-icon">FA</span><span>Financial Administration</span></button>
          </>}
          {currentAdmin?.permissions.includes("ADMIN_MANAGE") && <>
            <div className="sidebar-section-label">SYSTEM ADMINISTRATION</div>
            <button className={activeModule === "access" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("access")} aria-current={activeModule === "access" ? "page" : undefined}><span className="sidebar-icon">AC</span><span>Users &amp; access</span></button>
          </>}
          <div className="sidebar-note"><strong>Current release</strong><span>Organisation, membership, appointments and centrally governed financial configuration.</span></div>
        </aside>
        <main className="app-main">

      <section className="workspace-heading">
        <div>
          <div className="breadcrumb">{activeModule === "access" ? "SYSTEM ADMINISTRATION" : activeModule === "financial" ? "FINANCIAL ADMINISTRATION" : activeModule === "overview" ? "WORKSPACE" : "GENERAL ADMINISTRATION"}</div>
          <h1>{activeModule === "overview" ? "Overview" : activeModule === "organisation" ? "Organisational structure" : activeModule === "membership" ? "Membership" : activeModule === "appointments" ? "Church appointments" : activeModule === "financial" ? "Financial Administration" : "Users & access"}</h1>
          <p>{activeModule === "overview" ? "A summary of the organisation and administration areas available to your account." : activeModule === "organisation" ? "Create and maintain the organisation's geographical hierarchy." : activeModule === "membership" ? "Register and maintain members and their household records." : activeModule === "appointments" ? "Maintain church offices and date-effective member appointments." : activeModule === "financial" ? "Govern financial streams, contribution categories and geographical targets." : "Manage administrator accounts, roles, permissions and audit history."}</p>
        </div>
        <div className="record-stamp"><span>WORKSPACE</span><strong>{activeModule === "overview" ? "CEN-01" : activeModule === "organisation" ? "ORG-01" : activeModule === "membership" ? "MEM-01" : activeModule === "appointments" ? "APT-01" : activeModule === "financial" ? "FIN-01" : "ACL-01"}</strong><span>FOUNDATION RELEASE</span></div>
      </section>

      {(error || notice) && <div className={error ? "message error" : "message success"} role="status">{error || notice}<button onClick={() => { setError(""); setNotice(""); }} aria-label="Dismiss message">×</button></div>}

      {activeModule === "overview" && (
        <section className="central-overview">
          <div className="overview-context">
            <div><span className="overview-eyebrow">ORGANISATION IN VIEW</span><strong>{selectedOrganisation?.name ?? "No organisation registered"}</strong></div>
            <label>ACTIVE ORGANISATION<select value={organisationId} onChange={(e) => setOrganisationId(e.target.value)}><option value="">Select organisation</option>{organisations.map((org) => <option key={org.id} value={org.id}>{org.name}</option>)}</select></label>
          </div>
          <div className="overview-stat-grid">
            <article className="overview-stat"><span>ORGANISATIONS</span><strong>{organisations.length.toString().padStart(3, "0")}</strong></article>
            <article className="overview-stat"><span>BRANCHES</span><strong>{branches.length.toString().padStart(3, "0")}</strong></article>
            <article className="overview-stat"><span>GEOGRAPHICAL UNITS</span><strong>{unitCount.toString().padStart(3, "0")}</strong></article>
            <article className="overview-stat"><span>MEMBERS IN VIEW</span><strong>{members.length.toString().padStart(3, "0")}</strong></article>
          </div>
          <div className="overview-lower-grid">
            <section className="pos-window overview-window">
              <div className="pos-window-title"><div><span className="pos-window-icon">SYS</span><strong>CENTRAL ADMINISTRATION</strong></div><span className="pos-window-reference">CONTROL DESK</span></div>
              <div className="overview-module-list">
                <button onClick={() => setActiveModule("organisation")}><span className="module-code">01</span><span><strong>Organisation management</strong><small>Organisation records, branches and geographical hierarchy</small></span><b>OPEN</b></button>
                <button onClick={() => setActiveModule("membership")}><span className="module-code">02</span><span><strong>Membership administration</strong><small>Classifications, Cell registration and household records</small></span><b>OPEN</b></button>
                {currentAdmin?.permissions.includes("FINANCE_VIEW") && <button onClick={() => setActiveModule("financial")}><span className="module-code">03</span><span><strong>Financial Administration</strong><small>Financial streams, contribution categories and geographical targets</small></span><b>OPEN</b></button>}
                {currentAdmin?.permissions.includes("ADMIN_MANAGE") && <button onClick={() => setActiveModule("access")}><span className="module-code">04</span><span><strong>Administrator access control</strong><small>Accounts, role definitions, permissions and geographical scopes</small></span><b>OPEN</b></button>}
              </div>
            </section>
            <section className="pos-window overview-window">
              <div className="pos-window-title"><div><span className="pos-window-icon">POL</span><strong>ADMINISTRATIVE PRINCIPLES</strong></div><span className="pos-window-reference">DESIGN RULES</span></div>
              <div className="overview-principles">
                <div><strong>Central oversight</strong><p>Govern the organisation and its records.</p></div>
                <div><strong>Local administration</strong><p>Maintain records within assigned scope.</p></div>
                <div><strong>Controlled authority</strong><p>Roles control actions. Scope controls where they apply.</p></div>
              </div>
            </section>
          </div>
          <div className="overview-footnote">CURRENT RELEASE: ORGANISATION, MEMBERSHIP AND FINANCIAL CONFIGURATION · CONTRIBUTION POSTING NOT YET ENABLED</div>
        </section>
      )}

      {activeModule === "organisation" && (
        <>
          <div className="selection-bar organisation-selection">
            <label>Organisation<select value={organisationId} onChange={(e) => setOrganisationId(e.target.value)}><option value="">Select organisation</option>{organisations.map((org) => <option key={org.id} value={org.id}>{org.name}</option>)}</select></label>
            <div className="selection-summary"><span>BRANCHES IN ORGANISATION</span><strong>{branches.length.toString().padStart(3, "0")}</strong></div>
          </div>
          <div className="organisation-layout">
            <section className="panel">
              <div className="panel-title"><div><span className="panel-index">01</span><h2>Organisation record</h2></div><span className="panel-tag">CENTRAL CONTROL</span></div>
              <form onSubmit={createOrganisation} className="form-stack">
                <label>Organisation name<input name="name" required maxLength={160} placeholder="Organisation name" /></label>
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
              <div className="panel-title"><div><span className="panel-index">03</span><h2>Branch register</h2></div><span className="panel-tag">{branches.length} RECORDS</span></div>
              <div className="table-wrap"><table><thead><tr><th>CODE</th><th>BRANCH</th><th>DISTRICT / REGION</th></tr></thead><tbody>
                {branches.map((item) => <tr key={item.id}><td><strong>{item.code}</strong></td><td>{item.name}</td><td>{item.districtName || "—"}</td></tr>)}
                {!branches.length && <tr><td colSpan={3} className="empty-table">No branches registered.</td></tr>}
              </tbody></table></div>
            </section>
            <div className="organisation-hierarchy"><OrganisationalHierarchy organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} /></div>
          </div>
        </>
      )}

      {activeModule === "membership" && (
        <>
          <section className="selection-bar">
            <label>Organisation<select value={organisationId} onChange={(e) => setOrganisationId(e.target.value)}><option value="">Select organisation</option>{organisations.map((org) => <option key={org.id} value={org.id}>{org.name}</option>)}</select></label>
            <label>Legacy branch records<select value={branchId} onChange={(e) => setBranchId(e.target.value)} disabled={!branches.length}><option value="">Select branch</option>{branches.map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}</select></label>
            <div className="selection-summary"><span>LEGACY RECORDS IN VIEW</span><strong>{members.length.toString().padStart(3, "0")}</strong></div>
          </section>
          <div className="content-grid membership-grid">
            <section className="panel">
              <div className="panel-title"><div><span className="panel-index">01</span><h2>Member classifications</h2></div><span className="panel-tag">CONFIGURABLE</span></div>
              <p className="panel-description">Classification labels are configured by authorised administrators. No church-specific categories are embedded in the application.</p>
              <form onSubmit={createClassification} className="form-stack">
                <div className="field-pair"><label>Classification code<input name="code" required maxLength={60} placeholder="Classification code" /></label><label>Display name<input name="name" required maxLength={120} placeholder="Classification name" /></label></div>
                <button className="secondary-button" disabled={busy || !organisationId}>Add classification</button>
              </form>
              <div className="classification-list">
                {classifications.map((item) => <div className="classification-row" key={item.id}><span className="classification-code">{item.code}</span><span>{item.name}</span><span className="active-label">{item.active ? "ACTIVE" : "INACTIVE"}</span></div>)}
                {!classifications.length && <p className="empty-note">No classifications configured for this organisation.</p>}
              </div>
            </section>
            <section className="panel member-panel">
              <div className="panel-title"><div><span className="panel-index">02</span><h2>Legacy branch register</h2></div><span className="panel-tag">MEMBER RECORDS</span></div>
              <p className="panel-description">This is a read-only view of older branch-based records. Register new members in the Cell Membership section below so every new record has a canonical geographical location.</p>
              <div className="legacy-record-notice"><strong>LEGACY REGISTER</strong><span>Existing records are preserved. New member registration is handled through Cell Membership.</span></div>
              <div className="panel-divider" />
              <div className="register-heading"><div><h2>Membership register</h2><p>{selectedBranch ? `${selectedBranch.name}${selectedBranch.districtName ? " · " + selectedBranch.districtName : ""}` : "Select a branch to view its register"}</p></div><span className="count-box">{members.length} RECORDS</span></div>
              <label className="search-field"><span>SEARCH REGISTER</span><input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Name or ZCC app number" disabled={!branchId} /></label>
              <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>ZCC APP NO.</th><th>CLASSIFICATION</th><th>STATUS</th></tr></thead><tbody>
                {members.map((member) => <tr key={member.id}><td><strong>{member.lastName}, {member.firstName}</strong></td><td>{member.zccAppNumber || "—"}</td><td>{member.classificationName || "Unassigned"}</td><td><span className="active-label">{member.active ? "ACTIVE" : "INACTIVE"}</span></td></tr>)}
                {!members.length && <tr><td colSpan={4} className="empty-table">No legacy member records found for this branch.</td></tr>}
              </tbody></table></div>
            </section>
          </div>
          <CellMembership organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} />
        </>
      )}


      {activeModule === "appointments" && <MemberAppointments organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} />}

      {activeModule === "financial" && <FinancialAdministration organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} />}

      {activeModule === "access" && (
        <div className="access-layout">
          <section className="panel">
            <div className="panel-title"><div><span className="panel-index">01</span><h2>Administrator accounts</h2></div><span className="panel-tag">CENTRAL CONTROL</span></div>
            <form onSubmit={createAdminUser} className="form-stack">
              <label>Username<input name="username" required maxLength={80} /></label>
              <label>Display name<input name="displayName" required maxLength={160} /></label>
              <label>Temporary password<input name="password" type="password" required minLength={12} maxLength={100} /></label>
              <button className="primary-button" disabled={busy}>Create administrator</button>
            </form>
            <div className="panel-divider" />
            <div className="panel-title"><div><span className="panel-index">02</span><h2>Administrator register</h2></div><span className="panel-tag">{adminUsers.length} ACCOUNTS</span></div>
            <div className="table-wrap"><table><thead><tr><th>USERNAME</th><th>NAME</th><th>ROLE ASSIGNMENTS</th><th>STATUS</th></tr></thead><tbody>
              {adminUsers.map((user) => <tr key={user.id}><td><strong>{user.username}</strong></td><td>{user.displayName}</td><td>{user.assignments || "Unassigned"}</td><td>{user.enabled ? "ENABLED" : "DISABLED"}</td></tr>)}
              {!adminUsers.length && <tr><td colSpan={4} className="empty-table">No administrator accounts found.</td></tr>}
            </tbody></table></div>
          </section>
          <div className="access-right-column">
            <section className="panel">
              <div className="panel-title"><div><span className="panel-index">01</span><h2>Invite a Nyika administrator</h2></div><span className="panel-tag">EMAIL INVITATION</span></div>
              <p className="panel-description">Choose an active Nyika. The system assigns the administrator's username, role and geographical scope. The administrator creates their own password through a single-use email link.</p>
              <form onSubmit={createNyikaInvitation} className="form-stack">
                <label>Organisation<select value={accessOrganisationId} onChange={(e) => setAccessOrganisationId(e.target.value)} required><option value="">Select organisation</option>{organisations.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</select></label>
                <label>Nyika<select name="nyikaId" required defaultValue=""><option value="">Select active Nyika</option>{accessUnits.filter((u) => u.level === "NYIKA").map((u) => <option key={u.id} value={u.id}>{u.code} — {u.name}</option>)}</select></label>
                <label>Administrator name<input name="displayName" required maxLength={160} /></label>
                <label>Email address<input name="email" type="email" required maxLength={254} /></label>
                <button className="primary-button" disabled={busy || !accessOrganisationId}>Send invitation</button>
              </form>
              <div className="panel-divider" />
              <div className="panel-title"><div><span className="panel-index">02</span><h2>Invitation register</h2></div><span className="panel-tag">LATEST 100</span></div>
              <div className="table-wrap"><table><thead><tr><th>USERNAME</th><th>NYIKA</th><th>EMAIL</th><th>STATUS</th><th>ACTION</th></tr></thead><tbody>
                {invitations.map((invite) => <tr key={invite.id}><td><strong>{invite.username}</strong></td><td>{invite.nyikaCode} — {invite.nyikaName}</td><td>{invite.email}</td><td>{invite.status}</td><td>{invite.status === "PENDING" && <button className="secondary-button" disabled={busy} onClick={() => revokeNyikaInvitation(invite.id)}>Revoke</button>}</td></tr>)}
                {!invitations.length && <tr><td colSpan={5} className="empty-table">No Nyika invitations issued.</td></tr>}
              </tbody></table></div>
            </section>

            <section className="panel">
              <div className="panel-title"><div><span className="panel-index">03</span><h2>Assign role and scope</h2></div><span className="panel-tag">AUTHORITY</span></div>
              <form onSubmit={assignAdminRole} className="form-stack">
                <label>Administrator<select name="userId" required defaultValue=""><option value="">Select account</option>{adminUsers.map((u) => <option key={u.id} value={u.id}>{u.username} — {u.displayName}</option>)}</select></label>
                <label>Role<select name="roleId" required defaultValue=""><option value="">Select role</option>{adminRoles.map((r) => <option key={r.id} value={r.id}>{r.name} ({r.code})</option>)}</select></label>
                <label>Scope type<select name="scopeType" defaultValue="ORGANISATION"><option value="GLOBAL">Entire system</option><option value="ORGANISATION">Entire organisation</option><option value="UNIT">Geographical unit and descendants</option></select></label>
                <label>Organisation<select name="organisationId" value={accessOrganisationId} onChange={(e) => setAccessOrganisationId(e.target.value)}><option value="">Select organisation</option>{organisations.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</select></label>
                <label>Geographical unit<select name="scopeUnitId" defaultValue=""><option value="">Select unit</option>{accessUnits.map((u) => <option key={u.id} value={u.id}>{u.level} · {u.code} — {u.name}</option>)}</select></label>
                <button className="primary-button" disabled={busy}>Assign authority</button>
              </form>
              <p className="panel-description access-help">For a unit assignment, the administrator can act within that unit and its descendants only. Organisation and unit scope are checked by the API.</p>
            </section>
            <section className="panel">
              <div className="panel-title"><div><span className="panel-index">04</span><h2>Role configuration</h2></div><span className="panel-tag">CONFIGURABLE</span></div>
              <form onSubmit={createAdminRole} className="form-stack">
                <label>Role code<input name="code" required maxLength={60} placeholder="e.g. MEMBERSHIP_REVIEWER" /></label>
                <label>Role name<input name="name" required maxLength={120} /></label>
                <label>Description<input name="description" maxLength={300} /></label>
                <button className="secondary-button" disabled={busy}>Create custom role</button>
              </form>
              <div className="panel-divider" />
              <form onSubmit={saveRolePermissions} className="form-stack">
                <label>Role<select name="roleId" required defaultValue=""><option value="">Select custom role</option>{adminRoles.filter((r) => !r.systemRole).map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</select></label>
                <label>Permissions<select name="permissionCodes" multiple size={6}>{permissionOptions.map((p) => <option key={p.code} value={p.code}>{p.name} ({p.code})</option>)}</select></label>
                <button className="secondary-button" disabled={busy}>Save selected permissions</button>
              </form>
              <p className="panel-description access-help">System roles are protected. Create a custom role to define a different permission set. Use Ctrl or Command to select multiple permissions.</p>
            </section>
          </div>
          
            <section className="panel">
              <div className="panel-title"><div><span className="panel-index">05</span><h2>Administrator audit history</h2></div><span className="panel-tag">LATEST 250 EVENTS</span></div>
              <div className="table-wrap"><table><thead><tr><th>DATE / TIME</th><th>ACTOR</th><th>ACTION</th><th>RECORD</th><th>DETAILS</th></tr></thead><tbody>
                {auditEvents.map((event) => <tr key={event.id}><td>{new Date(event.occurredAt).toLocaleString()}</td><td>{event.actor}</td><td><strong>{event.action}</strong></td><td>{event.targetType}</td><td>{event.details}</td></tr>)}
                {!auditEvents.length && <tr><td colSpan={5} className="empty-table">No administrator events recorded.</td></tr>}
              </tbody></table></div>
            </section>
        </div>
      )}

      <footer className="system-footer"><span>ZCC BOOKS · CENTRAL MANAGEMENT</span><span>DEMONSTRATION DATA ONLY · NOT CONNECTED TO THE LIVE ZCC APP</span></footer>
        </main>
      </div>
    </main>
  );
}

function InvitationAcceptance() {
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [complete, setComplete] = useState(false);
  const [busy, setBusy] = useState(false);
  const token = new URLSearchParams(window.location.search).get("token") ?? "";

  async function activate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError("");
    if (!token) { setError("The invitation link is missing its token."); return; }
    if (password !== confirmation) { setError("The passwords do not match."); return; }
    setBusy(true);
    try {
      await api("/api/invitations/accept", { method: "POST", body: JSON.stringify({ token, password }) });
      setComplete(true);
    } catch (e) { setError(e instanceof Error ? e.message : "Invitation activation failed."); }
    finally { setBusy(false); }
  }

  return <main className="login-shell"><section className="login-panel">
    <div className="login-kicker">ZCC BOOKS · ADMINISTRATOR ACCESS</div>
    <h1>{complete ? "Account activated" : "Set your password"}</h1>
    {complete ? <><p>Your administrator account is active. Sign in using the username provided in your invitation email and the password you just created.</p><a href="/" className="primary-button" style={{display:"inline-block",textDecoration:"none",padding:"12px 16px"}}>Go to sign in</a></> :
      <><p>Create a password for your assigned Nyika administrator account. This invitation link can only be used once and expires after 48 hours.</p>
      {error && <div className="message error" role="alert">{error}</div>}
      <form className="form-stack" onSubmit={activate}>
        <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={12} maxLength={100} autoComplete="new-password" /></label>
        <label>Confirm password<input type="password" value={confirmation} onChange={(e) => setConfirmation(e.target.value)} required minLength={12} maxLength={100} autoComplete="new-password" /></label>
        <button className="primary-button" disabled={busy || !token}>{busy ? "Activating…" : "Activate administrator account"}</button>
      </form></>}
  </section></main>;
}

export default function App() {
  return window.location.pathname === "/accept-invitation" ? <InvitationAcceptance /> : <MainApp />;
}

