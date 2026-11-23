import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import OrganisationalHierarchy from "./OrganisationalHierarchy";
import NyikaAdminWorkspace from "./NyikaAdminWorkspace";
import CellMembership from "./CellMembership";
import MemberAppointments from "./MemberAppointments";
import MembershipReports from "./MembershipReports";
import FinancialAdministration from "./FinancialAdministration";
import FinancialOverview from "./FinancialOverview";
import FinancialReports from "./FinancialReports";
import FinancialJournals from "./FinancialJournals";
import FinancialLedgerReports from "./FinancialLedgerReports";
import ExpenditureManagement from "./ExpenditureManagement";
import BudgetManagement from "./BudgetManagement";
import DepositReview from "./DepositReview";
import AdminAuditLog from "./AdminAuditLog";

type Module = "overview" | "organisation" | "membership" | "appointments" | "membershipReports" | "financial" | "reports" | "journals" | "ledger" | "expenditure" | "budgets" | "depositReview" | "access";
type CurrentAdmin = { username: string; displayName: string; permissions: string[] };
type AdminUser = { id: string; username: string; displayName: string; email?: string | null; enabled: boolean; createdAt: string | null; assignments: string };
type AdminRole = { id: string; code: string; name: string; description?: string; systemRole: boolean; permissions: string[] };
type Permission = { code: string; name: string; description: string };
type UnitOption = { id: string; level: string; code: string; name: string };
type Invitation = { id: string; username: string; displayName: string; email: string; nyikaCode: string; nyikaName: string; expiresAt: string; status: string };
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
    setOrganisations(rows.slice(0, 1));
    if (!organisationId && rows.length) { setOrganisationId(rows[0].id); setAccessOrganisationId(rows[0].id); }
  }

  useEffect(() => { if (authenticated) refreshOrganisations().catch((e: Error) => setError(e.message)); }, [authenticated]);

  useEffect(() => {
    if (!authenticated || activeModule !== "access") return;
    Promise.all([
      api<AdminUser[]>("/api/admin/users"),
      api<AdminRole[]>("/api/admin/roles"),
      api<Permission[]>("/api/admin/permissions"),
      api<Invitation[]>("/api/admin/nyika-invitations"),
    ]).then(([users, roles, permissions, inviteRows]) => { setAdminUsers(users); setAdminRoles(roles); setPermissionOptions(permissions); setInvitations(inviteRows); })
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
      setUnitCount((unitResult.value as UnitOption[]).filter((unit) => unit.level === "NYIKA").length);
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
    setAdminUsers([]); setAdminRoles([]); setOrganisations([]); setBranches([]); setMembers([]); setAccessOrganisationId(""); setAccessUnits([]); setActiveModule("overview");
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

  const isNyikaAdministrator = !!currentAdmin
    && currentAdmin.permissions.includes("ORG_MANAGE")
    && currentAdmin.permissions.includes("MEMBERS_VIEW")
    && !currentAdmin.permissions.includes("ADMIN_MANAGE");

  if (isNyikaAdministrator) return (
    <main className="system">
      <header className="masthead">
        <div className="masthead-brand"><img className="brand-logo" src="/zcc-star-logo.png" alt="Zion Christian Church star" /><div><strong>ZCC BOOKS</strong><small>NYIKA ADMINISTRATION</small></div></div>
        <div className="masthead-right"><span>{currentAdmin?.displayName ?? "NYIKA ADMINISTRATOR"}</span><button className="logout-button" onClick={logout}>SIGN OUT</button><span className="system-state">SECURE SESSION</span></div>
      </header>
      <div className="nyika-app-main">
        <NyikaAdminWorkspace organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} />
        <footer className="system-footer"><span>ZCC BOOKS · NYIKA WORKSPACE</span><span>ACCESS LIMITED TO YOUR ASSIGNED NYIKA</span></footer>
      </div>
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

          {(currentAdmin?.permissions.includes("FINANCE_VIEW") || currentAdmin?.permissions.includes("FINANCE_CAPTURE") || currentAdmin?.permissions.includes("FINANCE_REVERSE") || currentAdmin?.permissions.includes("FINANCE_DEPOSIT") || currentAdmin?.permissions.includes("FINANCE_RECONCILE") || currentAdmin?.permissions.includes("FINANCE_REVIEW") || currentAdmin?.permissions.includes("FINANCE_EXPENSE_CAPTURE") || currentAdmin?.permissions.includes("FINANCE_EXPENSE_APPROVE") || currentAdmin?.permissions.includes("FINANCE_BUDGET_MANAGE") || currentAdmin?.permissions.includes("FINANCE_JOURNAL_VIEW") || currentAdmin?.permissions.includes("FINANCE_JOURNAL_CREATE") || currentAdmin?.permissions.includes("FINANCE_JOURNAL_APPROVE")) && <>
            <div className="sidebar-section-label">FINANCIAL ADMINISTRATION</div>
            {(currentAdmin?.permissions.includes("FINANCE_VIEW") || currentAdmin?.permissions.includes("FINANCE_CAPTURE") || currentAdmin?.permissions.includes("FINANCE_REVERSE") || currentAdmin?.permissions.includes("FINANCE_DEPOSIT") || currentAdmin?.permissions.includes("FINANCE_RECONCILE")) && <button className={activeModule === "financial" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("financial")} aria-current={activeModule === "financial" ? "page" : undefined}><span className="sidebar-icon">FA</span><span>Financial Administration</span></button>}
            {currentAdmin?.permissions.includes("FINANCE_VIEW") && <button className={activeModule === "reports" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("reports")} aria-current={activeModule === "reports" ? "page" : undefined}><span className="sidebar-icon">RP</span><span>Financial Reports</span></button>}
            {(currentAdmin?.permissions.includes("FINANCE_JOURNAL_VIEW") || currentAdmin?.permissions.includes("FINANCE_JOURNAL_CREATE") || currentAdmin?.permissions.includes("FINANCE_JOURNAL_APPROVE")) && <button className={activeModule === "journals" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("journals")} aria-current={activeModule === "journals" ? "page" : undefined}><span className="sidebar-icon">GL</span><span>Accounts &amp; Journals</span></button>}
            {currentAdmin?.permissions.includes("FINANCE_JOURNAL_VIEW") && <button className={activeModule === "ledger" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("ledger")} aria-current={activeModule === "ledger" ? "page" : undefined}><span className="sidebar-icon">TB</span><span>General Ledger Reports</span></button>}
            {currentAdmin?.permissions.includes("FINANCE_REVIEW") && <button className={activeModule === "depositReview" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("depositReview")} aria-current={activeModule === "depositReview" ? "page" : undefined}><span className="sidebar-icon">DR</span><span>Deposit package review</span></button>}
            {(currentAdmin?.permissions.includes("FINANCE_EXPENSE_CAPTURE") || currentAdmin?.permissions.includes("FINANCE_EXPENSE_APPROVE")) && <button className={activeModule === "expenditure" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("expenditure")} aria-current={activeModule === "expenditure" ? "page" : undefined}><span className="sidebar-icon">EX</span><span>Expenditure</span></button>}
            {(currentAdmin?.permissions.includes("FINANCE_VIEW") || currentAdmin?.permissions.includes("FINANCE_BUDGET_MANAGE")) && <button className={activeModule === "budgets" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("budgets")} aria-current={activeModule === "budgets" ? "page" : undefined}><span className="sidebar-icon">BG</span><span>Budgets</span></button>}
          </>}
          {currentAdmin?.permissions.includes("ADMIN_MANAGE") && <>
            <div className="sidebar-section-label">SYSTEM ADMINISTRATION</div>
            <button className={activeModule === "access" ? "sidebar-link active" : "sidebar-link"} onClick={() => setActiveModule("access")} aria-current={activeModule === "access" ? "page" : undefined}><span className="sidebar-icon">AC</span><span>Users &amp; access</span></button>
          </>}
          <div className="sidebar-note"><strong>Current release</strong><span>Organisation, membership, contributions, deposits, expenditure approvals, budget controls and financial reporting.</span></div>
        </aside>
        <main className="app-main">

      <section className="workspace-heading">
        <div>
          <div className="breadcrumb">{activeModule === "access" ? "SYSTEM ADMINISTRATION" : activeModule === "financial" ? "FINANCIAL ADMINISTRATION" : activeModule === "reports" ? "FINANCIAL REPORTING" : activeModule === "journals" || activeModule === "ledger" ? "GENERAL LEDGER" : activeModule === "expenditure" ? "EXPENDITURE CONTROL" : activeModule === "budgets" ? "BUDGET MANAGEMENT" : activeModule === "depositReview" ? "DEPOSIT PACKAGE REVIEW" : activeModule === "overview" ? "WORKSPACE" : "GENERAL ADMINISTRATION"}</div>
          <h1>{activeModule === "overview" ? "Overview" : activeModule === "organisation" ? "Organisational structure" : activeModule === "membership" ? "Membership" : activeModule === "appointments" ? "Church appointments" : activeModule === "financial" ? "Financial Administration" : activeModule === "reports" ? "Financial Reports" : activeModule === "journals" ? "Accounts & Journals" : activeModule === "ledger" ? "General Ledger Reports" : activeModule === "expenditure" ? "Expenditure Management" : activeModule === "budgets" ? "Budget Management" : activeModule === "depositReview" ? "Deposit Package Review" : "Users & access"}</h1>
          <p>{activeModule === "overview" ? "A summary of the organisation and administration areas available to your account." : activeModule === "organisation" ? "Create and maintain the organisation's geographical hierarchy." : activeModule === "membership" ? "Register and maintain members and their household records." : activeModule === "appointments" ? "Maintain church offices and date-effective member appointments." : activeModule === "financial" ? "Govern financial configuration, capture contributions and issue receipts within assigned scope." : activeModule === "reports" ? "Generate permission-scoped financial summaries, registers and downloadable Word reports." : activeModule === "journals" ? "Manage the chart of accounts, reconciliation mappings and controlled double-entry journals." : activeModule === "ledger" ? "Review trial balances and account statements generated from posted journals." : activeModule === "expenditure" ? "Capture church spending, enforce independent approval and track payment settlement." : activeModule === "budgets" ? "Allocate expenditure budgets, preserve amendments and monitor actual spending against approved allocations." : activeModule === "depositReview" ? "Review submitted Nyika deposit packages, record decisions and retain immutable review history." : "Manage administrator accounts, roles, permissions and audit history."}</p>
        </div>
        <div className="record-stamp"><span>WORKSPACE</span><strong>{activeModule === "overview" ? "CEN-01" : activeModule === "organisation" ? "ORG-01" : activeModule === "membership" ? "MEM-01" : activeModule === "appointments" ? "APT-01" : activeModule === "financial" ? "FIN-01" : activeModule === "reports" ? "RPT-01" : activeModule === "journals" ? "GL-01" : activeModule === "ledger" ? "GL-02" : activeModule === "expenditure" ? "EXP-01" : activeModule === "budgets" ? "BUD-01" : activeModule === "depositReview" ? "REV-01" : "ACL-01"}</strong><span>FOUNDATION RELEASE</span></div>
      </section>

      {(error || notice) && <div className={error ? "message error" : "message success"} role="status">{error || notice}<button onClick={() => { setError(""); setNotice(""); }} aria-label="Dismiss message">×</button></div>}

      {activeModule === "overview" && (
        <section className="central-overview">
          <div className="overview-context"><div><span className="overview-eyebrow">PERMANENT ORGANISATION</span><strong>Zion Christian Church</strong></div><span className="overview-eyebrow">SINGLE-ORGANISATION CONTROL CENTRE</span></div>
          <div className="overview-stat-grid">
            <article className="overview-stat"><span>ORGANISATION</span><strong>001</strong></article>
            <article className="overview-stat"><span>NYIKAS</span><strong>{unitCount.toString().padStart(3, "0")}</strong></article>
            <article className="overview-stat"><span>GEOGRAPHICAL UNITS</span><strong>{unitCount.toString().padStart(3, "0")}</strong></article>
            <article className="overview-stat"><span>CLASSIFICATIONS</span><strong>{classifications.length.toString().padStart(3, "0")}</strong></article>
          </div>
          <div className="overview-lower-grid">
            <section className="pos-window overview-window">
              <div className="pos-window-title"><div><span className="pos-window-icon">SYS</span><strong>CENTRAL ADMINISTRATION</strong></div><span className="pos-window-reference">CONTROL DESK</span></div>
              <div className="overview-module-list">
                <button onClick={() => setActiveModule("organisation")}><span className="module-code">01</span><span><strong>Organisation management</strong><small>Organisation records, branches and geographical hierarchy</small></span><b>OPEN</b></button>
                <button onClick={() => setActiveModule("organisation")}><span className="module-code">02</span><span><strong>Nyika configuration</strong><small>Configure countries, provinces, districts and Nyikas</small></span><b>OPEN</b></button>
                {(currentAdmin?.permissions.includes("FINANCE_VIEW") || currentAdmin?.permissions.includes("FINANCE_CAPTURE") || currentAdmin?.permissions.includes("FINANCE_REVERSE")) && <button onClick={() => setActiveModule("financial")}><span className="module-code">03</span><span><strong>Financial Administration</strong><small>Financial streams, contribution categories and geographical targets</small></span><b>OPEN</b></button>}
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
          <div className="overview-footnote">CURRENT RELEASE: ORGANISATION, MEMBERSHIP, CONTRIBUTIONS, DEPOSITS, EXPENDITURE AND BUDGET ACCOUNTABILITY</div>
        </section>
      )}

      {activeModule === "organisation" && (
        <>
          <div className="overview-context"><div><span className="overview-eyebrow">ORGANISATION</span><strong>Zion Christian Church</strong></div><span className="overview-eyebrow">COUNTRY → PROVINCE / STATE → DISTRICT → NYIKA</span></div>
          <div className="organisation-hierarchy central-structure-layout"><OrganisationalHierarchy organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} /></div>
        </>
      )}

      {activeModule === "financial" && <FinancialAdministration organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} canCapture={currentAdmin?.permissions.includes("FINANCE_CAPTURE") ?? false} canDeposit={currentAdmin?.permissions.includes("FINANCE_DEPOSIT") ?? false} canReconcile={currentAdmin?.permissions.includes("FINANCE_RECONCILE") ?? false} canManageExceptions={currentAdmin?.permissions.includes("FINANCE_EXCEPTION") ?? false} canReverse={currentAdmin?.permissions.includes("FINANCE_REVERSE") ?? false} />}

      {activeModule === "reports" && <FinancialReports organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} />}
      {activeModule === "journals" && <FinancialJournals organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} canView={currentAdmin?.permissions.includes("FINANCE_JOURNAL_VIEW") ?? false} canCreate={currentAdmin?.permissions.includes("FINANCE_JOURNAL_CREATE") ?? false} canApprove={currentAdmin?.permissions.includes("FINANCE_JOURNAL_APPROVE") ?? false} />}
      {activeModule === "ledger" && <FinancialLedgerReports organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} />}
      {activeModule === "depositReview" && <DepositReview organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} /> }

      {activeModule === "expenditure" && <ExpenditureManagement organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} canCapture={currentAdmin?.permissions.includes("FINANCE_EXPENSE_CAPTURE") ?? false} canApprove={currentAdmin?.permissions.includes("FINANCE_EXPENSE_APPROVE") ?? false} />}

      {activeModule === "budgets" && <BudgetManagement organisationId={organisationId} authorizationHeader={currentAuthHeader ?? ""} canManage={currentAdmin?.permissions.includes("FINANCE_BUDGET_MANAGE") ?? false} />}

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
            <AdminAuditLog authorizationHeader={currentAuthHeader ?? ""} />
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

