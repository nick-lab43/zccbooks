import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";

type AuditEvent = {
  id: string;
  actor: string;
  action: string;
  targetType: string;
  targetId: string | null;
  details: string;
  occurredAt: string;
};

type Filters = {
  from: string;
  to: string;
  action: string;
  actor: string;
  targetType: string;
  targetId: string;
  search: string;
};

const emptyFilters: Filters = {
  from: "", to: "", action: "", actor: "", targetType: "", targetId: "", search: "",
};

type Props = { authorizationHeader: string };

export default function AdminAuditLog({ authorizationHeader }: Props) {
  const [filters, setFilters] = useState<Filters>(emptyFilters);
  const [applied, setApplied] = useState<Filters>(emptyFilters);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [limit, setLimit] = useState(50);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
      Object.entries(applied).forEach(([key, value]) => {
        if (value.trim()) params.set(key, value.trim());
      });
      const response = await fetch(`${(import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "")}/api/admin/audit?${params}`, {
        headers: { Authorization: authorizationHeader },
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail ?? `Could not load audit history (${response.status}).`);
      }
      const rows = await response.json() as AuditEvent[];
      setEvents(rows);
      setHasMore(rows.length === limit);
    } catch (e) {
      setEvents([]);
      setHasMore(false);
      setError(e instanceof Error ? e.message : "Could not load audit history.");
    } finally {
      setLoading(false);
    }
  }, [authorizationHeader, applied, limit, offset]);

  useEffect(() => { void load(); }, [load]);

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setOffset(0);
    setApplied({ ...filters });
  }

  function clearFilters() {
    setFilters(emptyFilters);
    setApplied(emptyFilters);
    setOffset(0);
  }

  function update<K extends keyof Filters>(key: K, value: Filters[K]) {
    setFilters((current) => ({ ...current, [key]: value }));
  }

  return (
    <section className="panel audit-log-panel">
      <div className="panel-title">
        <div><span className="panel-index">05</span><h2>Administrator audit history</h2></div>
        <span className="panel-tag">{loading ? "LOADING" : `${events.length} EVENTS`}</span>
      </div>
      <p className="panel-description">Search the append-only record of administrator activity. Results are ordered newest first.</p>
      <form className="form-stack audit-filter-form" onSubmit={applyFilters}>
        <div className="field-pair">
          <label>From date<input type="date" value={filters.from} onChange={(e) => update("from", e.target.value)} /></label>
          <label>To date<input type="date" value={filters.to} onChange={(e) => update("to", e.target.value)} /></label>
        </div>
        <div className="field-pair">
          <label>Action<input value={filters.action} onChange={(e) => update("action", e.target.value)} maxLength={100} placeholder="e.g. MEMBER_CREATED" /></label>
          <label>Actor username<input value={filters.actor} onChange={(e) => update("actor", e.target.value)} maxLength={100} placeholder="Administrator username" /></label>
        </div>
        <div className="field-pair">
          <label>Target type<input value={filters.targetType} onChange={(e) => update("targetType", e.target.value)} maxLength={100} placeholder="e.g. MEMBER" /></label>
          <label>Target ID<input value={filters.targetId} onChange={(e) => update("targetId", e.target.value)} maxLength={100} placeholder="Record identifier" /></label>
        </div>
        <label>Search details<input value={filters.search} onChange={(e) => update("search", e.target.value)} maxLength={200} placeholder="Search action, actor, target or event details" /></label>
        <div className="audit-filter-actions">
          <button className="primary-button" type="submit" disabled={loading}>Apply filters</button>
          <button className="secondary-button" type="button" onClick={clearFilters} disabled={loading}>Clear filters</button>
          <label className="audit-page-size">Rows per page<select value={limit} onChange={(e) => { setLimit(Number(e.target.value)); setOffset(0); }}><option value={25}>25</option><option value={50}>50</option><option value={100}>100</option><option value={250}>250</option><option value={500}>500</option></select></label>
        </div>
      </form>
      {error && <div className="message error" role="alert">{error}</div>}
      <div className="table-wrap">
        <table><thead><tr><th>DATE / TIME</th><th>ACTOR</th><th>ACTION</th><th>TARGET</th><th>TARGET ID</th><th>DETAILS</th></tr></thead>
          <tbody>
            {events.map((item) => <tr key={item.id}>
              <td>{new Date(item.occurredAt).toLocaleString()}</td>
              <td>{item.actor}</td>
              <td><strong>{item.action}</strong></td>
              <td>{item.targetType}</td>
              <td>{item.targetId ?? "—"}</td>
              <td>{item.details}</td>
            </tr>)}
            {!loading && !error && events.length === 0 && <tr><td colSpan={6} className="empty-table">No audit events match the selected filters.</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="audit-pagination">
        <span>Showing {events.length ? offset + 1 : 0}–{offset + events.length}</span>
        <div>
          <button className="secondary-button" type="button" disabled={loading || offset === 0} onClick={() => setOffset((value) => Math.max(0, value - limit))}>Previous</button>
          <button className="secondary-button" type="button" disabled={loading || !hasMore} onClick={() => setOffset((value) => value + limit)}>Next</button>
        </div>
      </div>
    </section>
  );
}
