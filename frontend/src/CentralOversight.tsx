import { useEffect, useMemo, useState } from "react";

type Unit = { id: string; parentId: string | null; level: string; code: string; name: string; active: boolean };
type Member = { id: string; cellId: string; firstName: string; lastName: string; zccAppNumber: string | null; classificationName: string | null; active: boolean };
type Household = { id: string; code: string; name: string; householdHeadMemberId: string | null; active: boolean; cellId: string };
type HouseholdMember = { id: string; firstName: string; lastName: string; relationshipLabel: string; validFrom: string; validTo: string | null };
type Contribution = { id: string; receiptNumber: string; contributorName: string; categoryName: string; streamName: string; scopeName: string; amount: number; currency: string; contributionDate: string; paymentMethod: string; capturedAt: string };

export default function CentralOversight({ organisationId, authorizationHeader, canViewMembers, canViewFinance }: {
  organisationId: string; authorizationHeader: string; canViewMembers: boolean; canViewFinance: boolean;
}) {
  const [units, setUnits] = useState<Unit[]>([]);
  const [selectedNyikaId, setSelectedNyikaId] = useState("");
  const [members, setMembers] = useState<Member[]>([]);
  const [households, setHouseholds] = useState<Household[]>([]);
  const [householdMembers, setHouseholdMembers] = useState<Record<string, HouseholdMember[]>>({});
  const [contributions, setContributions] = useState<Contribution[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [financeUnavailable, setFinanceUnavailable] = useState(false);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

  async function request<T>(path: string): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, { headers: { Authorization: authorizationHeader } });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.json() as Promise<T>;
  }

  useEffect(() => {
    setUnits([]); setSelectedNyikaId("");
    if (!organisationId) return;
    request<Unit[]>(`/api/organisations/${organisationId}/units`)
      .then(rows => {
        setUnits(rows);
        const first = rows.find(unit => unit.level === "NYIKA" && unit.active);
        setSelectedNyikaId(first?.id ?? "");
      })
      .catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  const selectedNyika = units.find(unit => unit.id === selectedNyikaId);
  const descendants = useMemo(() => {
    if (!selectedNyikaId) return [];
    const result: Unit[] = [];
    const pending = [selectedNyikaId];
    while (pending.length) {
      const parentId = pending.shift()!;
      const children = units.filter(unit => unit.parentId === parentId);
      result.push(...children);
      pending.push(...children.map(unit => unit.id));
    }
    return result;
  }, [units, selectedNyikaId]);
  const cells = descendants.filter(unit => unit.level === "CELL" && unit.active);
  const financialScopes = [selectedNyika, ...descendants].filter((unit): unit is Unit => !!unit && unit.active && ["NYIKA", "TABHERA", "CELL"].includes(unit.level));

  useEffect(() => {
    let cancelled = false;
    setMembers([]); setHouseholds([]); setHouseholdMembers({}); setContributions([]);
    setError(""); setFinanceUnavailable(false);
    if (!selectedNyikaId) return;
    setLoading(true);
    const memberTasks = canViewMembers ? Promise.all(cells.map(async cell => {
      const [memberRows, householdRows] = await Promise.all([
        request<Omit<Member, "cellId">[]>(`/api/units/${cell.id}/members`),
        request<Omit<Household, "cellId">[]>(`/api/units/${cell.id}/households`)
      ]);
      return { cell, memberRows, householdRows };
    })) : Promise.resolve([]);
    const financeTasks = canViewFinance ? Promise.allSettled(financialScopes.map(unit =>
      request<Contribution[]>(`/api/organisations/${organisationId}/contributions?scopeUnitId=${unit.id}`)
    )) : Promise.resolve([]);
    Promise.all([memberTasks, financeTasks]).then(async ([memberResults, financeResults]) => {
      if (cancelled) return;
      const memberRows: Member[] = memberResults.flatMap(({ cell, memberRows }) => memberRows.map(member => ({ ...member, cellId: cell.id })));
      const householdRows: Household[] = memberResults.flatMap(({ cell, householdRows }) => householdRows.map(household => ({ ...household, cellId: cell.id })));
      setMembers(memberRows); setHouseholds(householdRows);
      const householdEntries = await Promise.all(householdRows.map(async household => {
        try { return [household.id, await request<HouseholdMember[]>(`/api/households/${household.id}/members`)] as const; }
        catch { return [household.id, []] as const; }
      }));
      if (cancelled) return;
      setHouseholdMembers(Object.fromEntries(householdEntries));
      if (canViewFinance) {
        const failed = financeResults.some(result => result.status === "rejected");
        setFinanceUnavailable(failed);
        setContributions(financeResults.flatMap(result => result.status === "fulfilled" ? result.value : []));
      }
    }).catch((e: Error) => { if (!cancelled) setError(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [organisationId, authorizationHeader, selectedNyikaId, canViewMembers, canViewFinance, cells.map(x => x.id).join(","), financialScopes.map(x => x.id).join(",")]);

  const activeMembers = members.filter(member => member.active);
  const activeHouseholds = households.filter(household => household.active);
  const contributionTotal = contributions.reduce((sum, row) => sum + Number(row.amount), 0);

  return <section className="panel">
    <div className="panel-title"><div><span className="panel-index">01</span><h2>Nyika oversight</h2></div><span className="panel-tag">CENTRAL READ-ONLY VIEW</span></div>
    <p className="panel-description">Inspect records maintained by local Nyika administrators. Central oversight is read-only; member registration and household maintenance remain with the local administration.</p>
    {error && <div className="message error" role="alert">{error}</div>}
    <label className="form-stack">Select Nyika
      <select value={selectedNyikaId} onChange={event => setSelectedNyikaId(event.target.value)}>
        <option value="">Select a Nyika</option>
        {units.filter(unit => unit.level === "NYIKA" && unit.active).sort((a,b) => a.name.localeCompare(b.name)).map(unit =>
          <option key={unit.id} value={unit.id}>{unit.name} · {unit.code}</option>)}
      </select>
    </label>
    {selectedNyika && <>
      <div className="overview-stat-grid" style={{marginTop:20}}>
        {canViewMembers && <>
          <article className="overview-stat"><span>ACTIVE MEMBERS</span><strong>{activeMembers.length}</strong></article>
          <article className="overview-stat"><span>ACTIVE HOUSEHOLDS</span><strong>{activeHouseholds.length}</strong></article>
        </>}
        <article className="overview-stat"><span>CELLS</span><strong>{cells.length}</strong></article>
        {canViewFinance && <article className="overview-stat"><span>CONTRIBUTIONS</span><strong>{contributions.length}</strong></article>}
        {canViewFinance && <article className="overview-stat"><span>RECORDED VALUE · ZAR</span><strong>{contributionTotal.toLocaleString("en-ZA", {minimumFractionDigits:2, maximumFractionDigits:2})}</strong></article>}
      </div>
      {loading && <p role="status">Loading records for {selectedNyika.name}…</p>}
      {canViewMembers && <>
        <div className="panel-divider" />
        <div className="register-heading"><div><h3>Member register</h3><p>Members grouped by their assigned Cell.</p></div><span className="count-box">{members.length} MEMBERS</span></div>
        <div className="table-wrap"><table><thead><tr><th>MEMBER</th><th>APPLICATION NUMBER</th><th>CLASSIFICATION</th><th>CELL</th><th>STATUS</th></tr></thead><tbody>
          {members.map(member => <tr key={member.id}><td>{member.firstName} {member.lastName}</td><td>{member.zccAppNumber || "—"}</td><td>{member.classificationName || "Unclassified"}</td><td>{units.find(unit => unit.id === member.cellId)?.name || "—"}</td><td>{member.active ? "ACTIVE" : "INACTIVE"}</td></tr>)}
          {!members.length && !loading && <tr><td colSpan={5} className="empty-table">No member records were found in this Nyika.</td></tr>}
        </tbody></table></div>
        <div className="panel-divider" />
        <div className="register-heading"><div><h3>Household register</h3><p>Household membership and designated household head.</p></div><span className="count-box">{households.length} HOUSEHOLDS</span></div>
        <div className="table-wrap"><table><thead><tr><th>HOUSEHOLD</th><th>CODE</th><th>HOUSEHOLD HEAD</th><th>MEMBERS</th><th>STATUS</th></tr></thead><tbody>
          {households.map(household => {
            const roster = householdMembers[household.id] ?? [];
            const head = members.find(member => member.id === household.householdHeadMemberId);
            return <tr key={household.id}><td>{household.name}</td><td>{household.code}</td><td>{head ? `${head.firstName} ${head.lastName}` : "Not assigned"}</td><td>{roster.filter(member => !member.validTo).length}</td><td>{household.active ? "ACTIVE" : "INACTIVE"}</td></tr>;
          })}
          {!households.length && !loading && <tr><td colSpan={5} className="empty-table">No household records were found in this Nyika.</td></tr>}
        </tbody></table></div>
      </>}
      {canViewFinance && <>
        <div className="panel-divider" />
        <div className="register-heading"><div><h3>Contribution register</h3><p>Receipts recorded against this Nyika, its Tabheras and Cells. Reversed contributions are excluded by the source register.</p></div><span className="count-box">{contributions.length} RECEIPTS</span></div>
        {financeUnavailable && <p className="message error">Some financial scopes could not be loaded with this account's permissions. The displayed totals may be incomplete.</p>}
        <div className="table-wrap"><table><thead><tr><th>RECEIPT</th><th>CONTRIBUTOR</th><th>CATEGORY / STREAM</th><th>DATE / SCOPE</th><th>AMOUNT</th></tr></thead><tbody>
          {contributions.map(row => <tr key={row.id}><td>{row.receiptNumber}</td><td>{row.contributorName}</td><td>{row.categoryName}<small className="table-subtext">{row.streamName}</small></td><td>{row.contributionDate}<small className="table-subtext">{row.scopeName}</small></td><td>{row.currency} {Number(row.amount).toFixed(2)}</td></tr>)}
          {!contributions.length && !loading && <tr><td colSpan={5} className="empty-table">No contribution records were found in the selected Nyika.</td></tr>}
        </tbody></table></div>
      </>}
    </>}
    {!canViewMembers && !canViewFinance && <p className="empty-note">This account does not have read permissions for member or financial records.</p>}
  </section>;
}
