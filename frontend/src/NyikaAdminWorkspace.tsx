import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Classification = { id: string; code: string; name: string; active: boolean };
type Household = { id: string; code: string; name: string; cellId: string; cellName: string; headMemberId: string | null; headMemberName: string | null; memberCount: number };
type HouseholdMember = { id: string; firstName: string; lastName: string; classificationId: string | null; classificationName: string | null; eliteNumber: string | null; zccAppNumber: string | null; active: boolean; relationshipLabel: string; joinedOn: string };
type HouseholdProfile = { household: Household; members: HouseholdMember[] };
type HouseholdCandidate = { id: string; firstName: string; lastName: string; classificationId: string | null; classificationName: string | null; eliteNumber: string | null; zccAppNumber: string | null };
type ContributionCategory = { id: string; name: string; streamName: string; active: boolean; scopeLevels: string[] };
type ContributionReceipt = { id: string; receiptNumber: string; memberId: string; memberName: string; categoryId: string; categoryName: string; streamName: string; scopeUnitId: string; scopeName: string; amount: number; currency: string; contributionDate: string; paymentMethod: string; paymentReference: string | null; note: string | null; capturedAt: string };
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
  const [selectedMember, setSelectedMember] = useState<Member | null>(null);
  const [households, setHouseholds] = useState<Household[]>([]);
  const [householdProfile, setHouseholdProfile] = useState<HouseholdProfile | null>(null);
  const [householdCellId, setHouseholdCellId] = useState("");
  const [householdCandidates, setHouseholdCandidates] = useState<HouseholdCandidate[]>([]);
  const [householdCandidateIds, setHouseholdCandidateIds] = useState<string[]>([]);
  const [householdHeadId, setHouseholdHeadId] = useState("");
  const [householdName, setHouseholdName] = useState("");
  const [householdFilter, setHouseholdFilter] = useState("");
  const [householdFilterIds, setHouseholdFilterIds] = useState<string[] | null>(null);
  const [addHouseholdMemberIds, setAddHouseholdMemberIds] = useState<string[]>([]);
  const [contributionCategories, setContributionCategories] = useState<ContributionCategory[]>([]);
  const [contributions, setContributions] = useState<ContributionReceipt[]>([]);
  const [contributionScopeId, setContributionScopeId] = useState("");
  const [contributionMode, setContributionMode] = useState<"MEMBER" | "HOUSEHOLD">("MEMBER");
  const [contributionMemberId, setContributionMemberId] = useState("");
  const [contributionHouseholdId, setContributionHouseholdId] = useState("");
  const [contributionReceipt, setContributionReceipt] = useState<ContributionReceipt | null>(null);
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
    const [rows, configuredClassifications, configuredCategories] = await Promise.all([
      request<Unit[]>(`/api/organisations/${organisationId}/units`),
      request<Classification[]>(`/api/organisations/${organisationId}/classifications`),
      request<ContributionCategory[]>(`/api/organisations/${organisationId}/contribution-categories`)
    ]);
    const nyika = rows.find(unit => unit.level === "NYIKA" && unit.active);
    setUnits(rows);
    setClassifications(configuredClassifications.filter(item => item.active));
    setContributionCategories(configuredCategories.filter(item => item.active));
    setNyikaId(nyika?.id ?? "");
    setContributionScopeId(current => current && rows.some(unit => unit.id === current && unit.active)
      ? current : (nyika?.id ?? ""));
    const tabs = rows.filter(unit => unit.level === "TABHERA" && unit.active && unit.parentId === nyika?.id);
    setTabheraId(current => tabs.some(tab => tab.id === current) ? current : tabs[0]?.id ?? "");
    if (nyika) {
      const [memberRows, householdRows] = await Promise.all([
        request<Member[]>(`/api/nyikas/${nyika.id}/members`),
        request<Household[]>(`/api/nyikas/${nyika.id}/households`)
      ]);
      setMembers(memberRows);
      setHouseholds(householdRows);
    } else {
      setMembers([]); setHouseholds([]); setContributions([]);
    }
  }

  useEffect(() => {
    setUnits([]); setMembers([]); setClassifications([]); setSelectedIds([]); setSelectedMember(null);
    setHouseholds([]); setHouseholdProfile(null); setHouseholdCandidates([]); setHouseholdCandidateIds([]); setHouseholdFilter(""); setHouseholdFilterIds(null);
    setContributionCategories([]); setContributions([]); setContributionScopeId(""); setContributionMemberId(""); setContributionHouseholdId(""); setContributionReceipt(null);
    setError(""); setNotice(""); setClassificationFilter(""); setCellFilter("");
    if (organisationId) reload().catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  useEffect(() => {
    if (!organisationId || !contributionScopeId) { setContributions([]); return; }
    request<ContributionReceipt[]>(
      `/api/organisations/${organisationId}/contributions?scopeUnitId=${contributionScopeId}`
    ).then(setContributions).catch((e: Error) => setError(e.message));
  }, [organisationId, contributionScopeId, authorizationHeader]);

  const tabheras = units.filter(unit => unit.level === "TABHERA" && unit.active && unit.parentId === nyikaId);
  const cells = units.filter(unit => unit.level === "CELL" && unit.active);
  const visibleMembers = members.filter(member => {
    const term = search.trim().toLowerCase();
    const matchesSearch = !term || `${member.firstName} ${member.lastName} ${member.zccAppNumber ?? ""} ${member.eliteNumber ?? ""}`.toLowerCase().includes(term);
    return matchesSearch
      && (!classificationFilter || member.classificationId === classificationFilter)
      && (!cellFilter || member.cellId === cellFilter)
      && (householdFilterIds === null || householdFilterIds.includes(member.id));
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

  async function openMemberProfile(memberId: string) {
    setError(""); setNotice("");
    try {
      const profile = await request<Member>(`/api/nyikas/${nyikaId}/members/${memberId}`);
      setSelectedMember(profile);
    } catch (e) { setError(e instanceof Error ? e.message : "Member profile could not be loaded."); }
  }

  async function saveMember(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      if (!selectedMember) return;
      const updated = await request<Member>(`/api/nyikas/${nyikaId}/members/${selectedMember.id}`, {
        method: "PUT", body: JSON.stringify({
          firstName: data.get("firstName"), lastName: data.get("lastName"),
          zccAppNumber: data.get("zccAppNumber"), eliteNumber: data.get("eliteNumber"),
          classificationId: data.get("classificationId"), cellId: data.get("cellId") || null
        })
      });
      await reload(); setSelectedMember(updated);
      setNotice("Member record updated. Changes have been recorded in the audit history.");
      form.reset();
    });
  }

  async function toggleMemberStatus(member: Member) {
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await request<Member>(`/api/nyikas/${nyikaId}/members/${member.id}/status`, {
        method: "POST", body: JSON.stringify({ active: !member.active })
      });
      await reload();
      setSelectedMember(current => current?.id === member.id ? updated : current);
      setNotice(member.active ? "Member deactivated. Their historical records are retained." : "Member reactivated.");
    } catch (e) { setError(e instanceof Error ? e.message : "Member status could not be changed."); }
    finally { setBusy(false); }
  }


  useEffect(() => {
    setHouseholdCandidateIds([]);
    if (!nyikaId || !householdCellId) { setHouseholdCandidates([]); return; }
    request<HouseholdCandidate[]>(`/api/nyikas/${nyikaId}/household-candidates?cellId=${householdCellId}`)
      .then(setHouseholdCandidates).catch((e: Error) => setError(e.message));
  }, [nyikaId, householdCellId, authorizationHeader]);

  function toggleHouseholdCandidate(id: string, checked: boolean) {
    const next = checked ? [...householdCandidateIds, id] : householdCandidateIds.filter(value => value !== id);
    setHouseholdCandidateIds(next);
    const selected = householdCandidates.filter(candidate => next.includes(candidate.id));
    const surnames = [...new Set(selected.map(candidate => candidate.lastName.trim().toLocaleLowerCase()))];
    if (selected.length && surnames.length === 1) setHouseholdName(`${selected[0].lastName.trim()} household`);
    else setHouseholdName("");
    setHouseholdHeadId(current => next.includes(current) ? current : "");
  }

  async function refreshHouseholdCandidates(cellId: string) {
    if (!cellId) { setHouseholdCandidates([]); return; }
    const rows = await request<HouseholdCandidate[]>(`/api/nyikas/${nyikaId}/household-candidates?cellId=${cellId}`);
    setHouseholdCandidates(rows);
  }

  async function createHousehold(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      if (!householdCellId || !householdCandidateIds.length) throw new Error("Select a Cell and at least one available member.");
      if (!householdName.trim()) throw new Error("Enter a household name.");
      const created = await request<HouseholdProfile>(`/api/nyikas/${nyikaId}/households`, {
        method: "POST", body: JSON.stringify({
          cellId: householdCellId, code: data.get("householdCode"), name: householdName.trim(),
          headMemberId: householdHeadId || null,
          members: householdCandidateIds.map(memberId => ({ memberId, relationshipLabel: "Member" }))
        })
      });
      await reload(); setHouseholdProfile(created); setHouseholdCandidateIds([]);
      setHouseholdHeadId(""); setHouseholdName(""); setHouseholdCellId(created.household.cellId); await refreshHouseholdCandidates(created.household.cellId); form.reset();
      setNotice("Household created. Member links are recorded in the membership history.");
    });
  }

  async function openHousehold(id: string) {
    setError(""); setNotice("");
    try {
      const profile = await request<HouseholdProfile>(`/api/nyikas/${nyikaId}/households/${id}`);
      setHouseholdProfile(profile); setAddHouseholdMemberIds([]); setHouseholdCellId(profile.household.cellId);
    } catch (e) { setError(e instanceof Error ? e.message : "Household could not be loaded."); }
  }

  async function addMembersToHousehold() {
    if (!householdProfile || !addHouseholdMemberIds.length) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await request<HouseholdProfile>(`/api/nyikas/${nyikaId}/households/${householdProfile.household.id}/members`, {
        method: "POST", body: JSON.stringify({ members: addHouseholdMemberIds.map(memberId => ({ memberId, relationshipLabel: "Member" })) })
      });
      setHouseholdProfile(updated); setAddHouseholdMemberIds([]); await reload(); await refreshHouseholdCandidates(updated.household.cellId);
      setNotice("Members added to the household.");
    } catch (e) { setError(e instanceof Error ? e.message : "Members could not be added."); }
    finally { setBusy(false); }
  }

  async function removeHouseholdMember(memberId: string) {
    if (!householdProfile) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await request<HouseholdProfile>(`/api/nyikas/${nyikaId}/households/${householdProfile.household.id}/members/${memberId}`, { method: "DELETE" });
      setHouseholdProfile(updated); await reload(); await refreshHouseholdCandidates(updated.household.cellId);
      setNotice("Membership ended. The historical membership record is retained.");
    } catch (e) { setError(e instanceof Error ? e.message : "Member could not be removed."); }
    finally { setBusy(false); }
  }

  async function changeHouseholdHead(memberId: string) {
    if (!householdProfile) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await request<HouseholdProfile>(`/api/nyikas/${nyikaId}/households/${householdProfile.household.id}/head`, {
        method: "PUT", body: JSON.stringify({ memberId: memberId || null })
      });
      setHouseholdProfile(updated); await reload();
      setNotice(memberId ? "Household head updated." : "Household head designation cleared.");
    } catch (e) { setError(e instanceof Error ? e.message : "Household head could not be changed."); }
    finally { setBusy(false); }
  }

  function isWithinScope(cellId: string | null, scopeId: string) {
    if (!cellId || !scopeId) return false;
    let current = units.find(unit => unit.id === cellId);
    while (current) {
      if (current.id === scopeId) return true;
      current = current.parentId ? units.find(unit => unit.id === current?.parentId) : undefined;
    }
    return false;
  }

  const selectedContributionScope = units.find(unit => unit.id === contributionScopeId);
  const contributionScopeUnits = units.filter(unit => unit.active
    && ["NYIKA", "TABHERA", "CELL"].includes(unit.level)
    && isWithinScope(unit.id, nyikaId));
  const applicableContributionCategories = contributionCategories.filter(category =>
    selectedContributionScope && category.scopeLevels.includes(selectedContributionScope.level));
  const eligibleContributionMembers = members.filter(member => member.active
    && isWithinScope(member.cellId, contributionScopeId));
  const eligibleContributionHouseholds = households.filter(household =>
    !!household.headMemberId && isWithinScope(household.cellId, contributionScopeId));

  async function captureContribution(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    await submit(event, async data => {
      if (!contributionScopeId) throw new Error("Select a financial scope.");
      if (!applicableContributionCategories.length) throw new Error("Central Administration has not configured an active category for this financial level.");
      const memberId = contributionMode === "MEMBER"
        ? contributionMemberId
        : (households.find(item => item.id === contributionHouseholdId)?.headMemberId ?? "");
      if (!memberId) throw new Error(contributionMode === "HOUSEHOLD"
        ? "Select a household with an active designated household head."
        : "Select a member.");
      const result = await request<ContributionReceipt>(`/api/organisations/${organisationId}/contributions`, {
        method: "POST",
        body: JSON.stringify({
          memberId,
          householdId: contributionMode === "HOUSEHOLD" ? contributionHouseholdId : null,
          categoryId: data.get("categoryId"),
          scopeUnitId: contributionScopeId,
          amount: data.get("amount"),
          contributionDate: data.get("contributionDate"),
          paymentMethod: data.get("paymentMethod"),
          paymentReference: data.get("paymentReference"),
          note: data.get("note"),
          idempotencyKey: crypto.randomUUID()
        })
      });
      setContributionReceipt(result);
      const rows = await request<ContributionReceipt[]>(
        `/api/organisations/${organisationId}/contributions?scopeUnitId=${contributionScopeId}`
      );
      setContributions(rows);
      setContributionMemberId(""); setContributionHouseholdId(""); form.reset();
      setNotice(`Contribution recorded. Receipt ${result.receiptNumber} issued.`);
    });
  }

  async function changeHouseholdFilter(id: string) {
    setHouseholdFilter(id); setHouseholdFilterIds(null);
    if (!id) return;
    try {
      const profile = await request<HouseholdProfile>(`/api/nyikas/${nyikaId}/households/${id}`);
      setHouseholdFilterIds(profile.members.map(member => member.id));
    } catch (e) { setError(e instanceof Error ? e.message : "Household filter could not be loaded."); }
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
          <label>Elite Number <span className="nyika-optional">(optional)</span><input name="eliteNumber" /></label>
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
        <select aria-label="Filter by household" value={householdFilter} onChange={e => changeHouseholdFilter(e.target.value)}><option value="">All households</option>{households.map(household => <option key={household.id} value={household.id}>{household.name}</option>)}</select>
        <select aria-label="Destination Cell" value={cellId} onChange={e => setCellId(e.target.value)}><option value="">Choose destination Cell</option>{cells.map(cell => <option key={cell.id} value={cell.id}>{cell.code} — {cell.name}</option>)}</select>
        <button className="primary-button" type="button" disabled={busy || !cellId || !selectedIds.length} onClick={assignSelected}>Assign selected ({selectedIds.length})</button>
      </div>
      {selectedMember && <section className="nyika-card nyika-member-profile">
        <div className="nyika-register-heading"><div><span className="nyika-eyebrow">MEMBER PROFILE</span><h3>{selectedMember.lastName}, {selectedMember.firstName}</h3><p>Review and maintain the member's details within your Nyika.</p></div><button className="secondary-button" type="button" onClick={() => setSelectedMember(null)}>Close profile</button></div>
        <div className="nyika-profile-facts">
          <div><span>MEMBER STATUS</span><strong>{selectedMember.active ? "Active" : "Inactive"}</strong></div>
          <div><span>CLASSIFICATION</span><strong>{selectedMember.classificationName || "Not assigned"}</strong></div>
          <div><span>ELITE NUMBER</span><strong>{selectedMember.eliteNumber || "Not recorded"}</strong></div>
          <div><span>ZCC APPLICATION NO.</span><strong>{selectedMember.zccAppNumber || "Not recorded"}</strong></div>
          <div><span>CELL</span><strong>{selectedMember.cellName || "Awaiting assignment"}</strong></div>
        </div>
        <form className="form-stack nyika-edit-form" key={selectedMember.id} onSubmit={saveMember}>
          <div className="field-pair"><label>First name<input name="firstName" defaultValue={selectedMember.firstName} required maxLength={100} /></label><label>Last name<input name="lastName" defaultValue={selectedMember.lastName} required maxLength={100} /></label></div>
          <label>Classification<select name="classificationId" defaultValue={selectedMember.classificationId ?? ""} required>{classifications.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <div className="field-pair"><label>Elite Number<input name="eliteNumber" defaultValue={selectedMember.eliteNumber ?? ""} /></label><label>ZCC application number<input name="zccAppNumber" defaultValue={selectedMember.zccAppNumber ?? ""} maxLength={80} /></label></div>
          <label>Cell assignment<select name="cellId" defaultValue={selectedMember.cellId ?? ""}><option value="">Unassigned</option>{cells.map(cell => <option key={cell.id} value={cell.id}>{cell.code} — {cell.name}</option>)}</select></label>
          <div className="nyika-profile-actions"><button className="primary-button" disabled={busy}>Save member details</button><button className="secondary-button" type="button" disabled={busy} onClick={() => toggleMemberStatus(selectedMember)}>{selectedMember.active ? "Deactivate member" : "Reactivate member"}</button></div>
        </form>
      </section>}
      <div className="table-wrap"><table><thead><tr><th><input type="checkbox" aria-label="Select all visible unassigned members" checked={allVisibleSelected} onChange={e => setSelectedIds(current => e.target.checked ? [...new Set([...current, ...selectableVisibleMembers.map(member => member.id)])] : current.filter(id => !visibleMembers.some(member => member.id === id)))} /></th><th>MEMBER</th><th>CLASSIFICATION</th><th>ELITE NUMBER</th><th>ZCC NUMBER</th><th>CELL</th><th>STATUS</th><th>ACTION</th></tr></thead><tbody>
        {visibleMembers.map(member => <tr key={member.id}><td><input type="checkbox" aria-label={`Select ${member.firstName} ${member.lastName}`} checked={selectedIds.includes(member.id)} disabled={!!member.cellId || !member.active} onChange={e => toggleMember(member.id, e.target.checked)} /></td><td><strong>{member.lastName}, {member.firstName}</strong></td><td>{member.classificationName || "—"}</td><td>{member.eliteNumber || "—"}</td><td>{member.zccAppNumber || "—"}</td><td>{member.cellName || <span className="nyika-unassigned">Awaiting assignment</span>}</td><td>{member.active ? "Active" : "Inactive"}</td><td><button className="secondary-button nyika-row-action" type="button" onClick={() => openMemberProfile(member.id)}>Open profile</button></td></tr>)}
        {!visibleMembers.length && <tr><td colSpan={8} className="empty-table">No members match these filters.</td></tr>}
      </tbody></table></div>
    </section>


    <section className="nyika-card nyika-households">
      <div className="nyika-register-heading"><div><span className="nyika-eyebrow">HOUSEHOLD MANAGEMENT</span><h3>Build and maintain households</h3><p>Households belong to a Cell. Select members deliberately; matching surnames only provide a suggested title.</p></div><span className="nyika-register-count">{households.length} HOUSEHOLDS</span></div>
      <div className="nyika-household-grid">
        <form className="form-stack nyika-household-create" onSubmit={createHousehold}>
          <h4>Create a household</h4>
          <label>Cell<select value={householdCellId} onChange={e => { setHouseholdCellId(e.target.value); setHouseholdCandidateIds([]); setHouseholdHeadId(""); }} required><option value="">Select Cell</option>{cells.map(cell => <option key={cell.id} value={cell.id}>{cell.code} — {cell.name}</option>)}</select></label>
          <label>Household code<input name="householdCode" required maxLength={60} placeholder="Enter a unique code" /></label>
          <label>Household name<input value={householdName} onChange={e => setHouseholdName(e.target.value)} required maxLength={160} placeholder="Enter household name" /></label>
          {householdCandidateIds.length > 0 && <label>Household head<select value={householdHeadId} onChange={e => setHouseholdHeadId(e.target.value)}><option value="">No head designated yet</option>{householdCandidates.filter(candidate => householdCandidateIds.includes(candidate.id)).map(candidate => <option key={candidate.id} value={candidate.id}>{candidate.lastName}, {candidate.firstName}</option>)}</select></label>}
          <div className="nyika-household-candidates"><strong>Available members in this Cell</strong>{householdCandidates.map(candidate => <label key={candidate.id} className="nyika-household-candidate"><input type="checkbox" checked={householdCandidateIds.includes(candidate.id)} onChange={e => toggleHouseholdCandidate(candidate.id, e.target.checked)} /><span><b>{candidate.lastName}, {candidate.firstName}</b><small>{candidate.classificationName || "No classification"} · {candidate.eliteNumber || "No Elite Number"}</small></span></label>)}{householdCellId && !householdCandidates.length && <p>No available active members in this Cell. Members already in another household are excluded.</p>}{!householdCellId && <p>Select a Cell to load available members.</p>}</div>
          <button className="primary-button" disabled={busy || !householdCellId || !householdCandidateIds.length}>Create household ({householdCandidateIds.length})</button>
        </form>
        <div className="nyika-household-list">
          <h4>Existing households</h4>
          {households.map(household => <article key={household.id} className="nyika-household-row"><div><strong>{household.name}</strong><small>{household.code} · {household.cellName}</small><small>{household.memberCount} member(s) · Head: {household.headMemberName || "Not assigned"}</small></div><button className="secondary-button nyika-row-action" type="button" onClick={() => openHousehold(household.id)}>Open</button></article>)}
          {!households.length && <p>No households have been created in this Nyika.</p>}
        </div>
      </div>
      {householdProfile && <section className="nyika-household-profile">
        <div className="nyika-register-heading"><div><span className="nyika-eyebrow">HOUSEHOLD PROFILE</span><h3>{householdProfile.household.name}</h3><p>{householdProfile.household.code} · {householdProfile.household.cellName}</p></div><button className="secondary-button" type="button" onClick={() => setHouseholdProfile(null)}>Close</button></div>
        <div className="nyika-household-head"><label>Household head<select value={householdProfile.household.headMemberId ?? ""} onChange={e => changeHouseholdHead(e.target.value)} disabled={busy}><option value="">No head designated</option>{householdProfile.members.filter(member => member.active).map(member => <option key={member.id} value={member.id}>{member.lastName}, {member.firstName}</option>)}</select></label><span>{householdProfile.members.length} active membership(s)</span></div>
        <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>CLASSIFICATION</th><th>ELITE NUMBER</th><th>ZCC NUMBER</th><th>RELATIONSHIP</th><th>JOINED</th><th>ACTION</th></tr></thead><tbody>
          {householdProfile.members.map(member => <tr key={member.id}><td><strong>{member.lastName}, {member.firstName}</strong>{householdProfile.household.headMemberId === member.id && <small className="nyika-household-head-label">HOUSEHOLD HEAD</small>}</td><td>{member.classificationName || "—"}</td><td>{member.eliteNumber || "—"}</td><td>{member.zccAppNumber || "—"}</td><td>{member.relationshipLabel}</td><td>{member.joinedOn}</td><td><button className="secondary-button nyika-row-action" type="button" disabled={busy || householdProfile.household.headMemberId === member.id} onClick={() => removeHouseholdMember(member.id)}>End membership</button></td></tr>)}
          {!householdProfile.members.length && <tr><td colSpan={7} className="empty-table">No active members in this household.</td></tr>}
        </tbody></table></div>
        <div className="nyika-household-add"><strong>Add available members from {householdProfile.household.cellName}</strong><div className="nyika-household-add-list">{householdCandidates.map(candidate => <label key={candidate.id}><input type="checkbox" checked={addHouseholdMemberIds.includes(candidate.id)} onChange={e => setAddHouseholdMemberIds(current => e.target.checked ? [...current, candidate.id] : current.filter(id => id !== candidate.id))} /><span>{candidate.lastName}, {candidate.firstName}</span></label>)}{!householdCandidates.length && <small>No unassigned active members are available in this Cell.</small>}</div><button className="primary-button" type="button" disabled={busy || !addHouseholdMemberIds.length} onClick={addMembersToHousehold}>Add selected members ({addHouseholdMemberIds.length})</button></div>
      </section>}
    </section>


    <section className="nyika-card nyika-contributions">
      <div className="nyika-register-heading"><div><span className="nyika-eyebrow">FINANCIAL WORKSPACE</span><h3>Capture contributions</h3><p>Record a contribution for an individual member or a household. Only active categories configured by Central Administration are available.</p></div><span className="nyika-register-count">{contributions.length} RECENT RECORDS</span></div>
      <form className="form-stack nyika-contribution-form" onSubmit={captureContribution}>
        <label>Financial scope<select value={contributionScopeId} onChange={e => { setContributionScopeId(e.target.value); setContributionMemberId(""); setContributionHouseholdId(""); setContributionReceipt(null); }} required><option value="">Select authorised scope</option>{contributionScopeUnits.map(unit => <option key={unit.id} value={unit.id}>{unit.level} · {unit.code} — {unit.name}</option>)}</select></label>
        <div className="nyika-contribution-mode"><button type="button" className={contributionMode === "MEMBER" ? "primary-button" : "secondary-button"} onClick={() => { setContributionMode("MEMBER"); setContributionHouseholdId(""); }}>Individual member</button><button type="button" className={contributionMode === "HOUSEHOLD" ? "primary-button" : "secondary-button"} onClick={() => { setContributionMode("HOUSEHOLD"); setContributionMemberId(""); }}>Household</button></div>
        {contributionMode === "MEMBER" ? <label>Member<select value={contributionMemberId} onChange={e => setContributionMemberId(e.target.value)} required disabled={!contributionScopeId}><option value="">Select active member</option>{eligibleContributionMembers.map(member => <option key={member.id} value={member.id}>{member.lastName}, {member.firstName}{member.eliteNumber ? ` · Elite ${member.eliteNumber}` : ""}</option>)}</select></label>
          : <label>Household<select value={contributionHouseholdId} onChange={e => setContributionHouseholdId(e.target.value)} required disabled={!contributionScopeId}><option value="">Select household with designated head</option>{eligibleContributionHouseholds.map(household => <option key={household.id} value={household.id}>{household.name} · Head: {household.headMemberName}</option>)}</select><small className="nyika-form-hint">The designated household head is retained as the member attribution for the existing finance ledger. The receipt and contribution register identify the household.</small></label>}
        <label>Contribution category<select name="categoryId" required defaultValue="" disabled={!applicableContributionCategories.length}><option value="">Select configured category</option>{applicableContributionCategories.map(category => <option key={category.id} value={category.id}>{category.name} · {category.streamName}</option>)}</select></label>
        <div className="field-pair"><label>Amount (ZAR)<input name="amount" type="number" min="0.01" step="0.01" required placeholder="0.00" /></label><label>Contribution date<input name="contributionDate" type="date" required defaultValue={new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10)} /></label></div>
        <div className="field-pair"><label>Payment method<select name="paymentMethod" defaultValue="CASH"><option value="CASH">Cash</option><option value="BANK_TRANSFER">Bank transfer</option><option value="CARD">Card</option><option value="MOBILE_MONEY">Mobile money</option><option value="OTHER">Other</option></select></label><label>Payment reference<input name="paymentReference" maxLength={120} placeholder="Optional reference" /></label></div>
        <label>Note <span className="nyika-optional">(optional)</span><input name="note" maxLength={500} placeholder="Additional information" /></label>
        {!contributionCategories.length && <p className="nyika-form-hint">No contribution categories are configured. Central Administration must configure categories and scope levels first.</p>}
        {contributionCategories.length > 0 && selectedContributionScope && !applicableContributionCategories.length && <p className="nyika-form-hint">No active categories are configured for the selected {selectedContributionScope.level.toLowerCase()} scope.</p>}
        <button className="primary-button" disabled={busy || !contributionScopeId || !applicableContributionCategories.length || (contributionMode === "MEMBER" ? !contributionMemberId : !contributionHouseholdId)}>Post contribution and issue receipt</button>
      </form>
      {contributionReceipt && <div className="nyika-contribution-receipt" role="status"><div><span className="nyika-eyebrow">RECEIPT ISSUED</span><strong>{contributionReceipt.receiptNumber}</strong><span>{contributionReceipt.memberName} · {contributionReceipt.categoryName}</span><b>{contributionReceipt.currency} {Number(contributionReceipt.amount).toLocaleString("en-ZA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</b><small>{contributionReceipt.contributionDate} · {contributionReceipt.paymentMethod.replace(/_/g, " ")}</small></div><button className="secondary-button" type="button" onClick={() => window.print()}>Print receipt</button></div>}
      <div className="nyika-contribution-history"><div className="nyika-register-heading"><div><h4>Contribution history</h4><p>Posted contributions in the selected financial scope. Reversed receipts are excluded from this active history.</p></div></div>
        <div className="table-wrap"><table><thead><tr><th>RECEIPT / DATE</th><th>CONTRIBUTOR</th><th>CATEGORY</th><th>AMOUNT</th><th>PAYMENT</th></tr></thead><tbody>
          {contributions.slice(0, 15).map(item => <tr key={item.id}><td><strong>{item.receiptNumber}</strong><small className="table-subtext">{item.contributionDate}</small></td><td>{item.memberName}</td><td>{item.categoryName}</td><td>{item.currency} {Number(item.amount).toLocaleString("en-ZA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</td><td>{item.paymentMethod.replace(/_/g, " ")}</td></tr>)}
          {!contributions.length && <tr><td colSpan={5} className="empty-table">No contributions have been recorded in this scope.</td></tr>}
        </tbody></table></div>
      </div>
    </section>

    <section className="nyika-next-step">
      <div><span className="nyika-eyebrow">NEXT IN YOUR WORKSPACE</span><h3>Financial reporting</h3><p>Contribution summaries by period, category, Cell and household are the next reporting milestone.</p></div>
      <span className="nyika-next-status">UP NEXT</span>
    </section>
  </div>;
}
