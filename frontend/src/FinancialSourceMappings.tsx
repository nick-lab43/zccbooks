import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

type Account = { id: string; code: string; name: string; accountType: string; currency: string; active: boolean };
type Category = { id: string; code: string; name: string; active: boolean };
type Mapping = { id: string; sourceType: string; categoryId: string; categoryCode: string; categoryName: string; paymentMethod: string | null; currency: string; debitAccountId: string; debitAccountCode: string; debitAccountName: string; creditAccountId: string; creditAccountCode: string; creditAccountName: string };
type Options = { accounts: Account[]; contributionCategories: Category[]; expenditureCategories: Category[]; contributionMappings: Mapping[]; expenditureMappings: Mapping[] };

export default function FinancialSourceMappings({ organisationId, authorizationHeader }: { organisationId: string; authorizationHeader: string }) {
  const [options, setOptions] = useState<Options | null>(null);
  const [sourceType, setSourceType] = useState<"CONTRIBUTION" | "EXPENDITURE">("CONTRIBUTION");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");
  const headers = useMemo(() => ({ Authorization: authorizationHeader }), [authorizationHeader]);

  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, {
      ...init,
      headers: { "Content-Type": "application/json", ...headers, ...init?.headers }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  async function load() {
    setOptions(await request<Options>(`/api/organisations/${organisationId}/finance-source-account-mappings/options`));
  }

  useEffect(() => {
    if (!organisationId) return;
    load().catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    try {
      await request<void>(`/api/organisations/${organisationId}/finance-source-account-mappings`, {
        method: "PUT",
        body: JSON.stringify({
          sourceType,
          categoryId: form.get("categoryId"),
          paymentMethod: sourceType === "CONTRIBUTION" ? form.get("paymentMethod") : null,
          currency: "ZAR",
          debitAccountId: form.get("debitAccountId"),
          creditAccountId: form.get("creditAccountId")
        })
      });
      await load();
      setNotice("Source-to-ledger account mapping saved.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Mapping could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  const categories = sourceType === "CONTRIBUTION" ? options?.contributionCategories ?? [] : options?.expenditureCategories ?? [];
  const debitType = sourceType === "CONTRIBUTION" ? "ASSET" : "EXPENSE";
  const creditType = sourceType === "CONTRIBUTION" ? "INCOME" : "ASSET";
  const accounts = (type: string) => (options?.accounts ?? []).filter(a => a.active && a.currency === "ZAR" && a.accountType === type);
  const mappings = [...(options?.contributionMappings ?? []), ...(options?.expenditureMappings ?? [])];

  return <section className="journal-source-mappings">
    <div className="panel-divider" />
    <div className="journal-section-heading"><div><h3>Contribution and expenditure account mappings</h3><p>Define the accounts to use when operational source records are connected to the general ledger. Saving a mapping does not create or post a journal.</p></div><span>SOURCE CONFIGURATION</span></div>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    <form className="form-stack journal-form" onSubmit={save}>
      <div className="field-pair">
        <label>Source type<select value={sourceType} onChange={e => setSourceType(e.target.value as "CONTRIBUTION" | "EXPENDITURE")}><option value="CONTRIBUTION">Contribution</option><option value="EXPENDITURE">Expenditure</option></select></label>
        <label>Category<select key={sourceType} name="categoryId" required defaultValue=""><option value="">Select category</option>{categories.filter(c => c.active).map(c => <option key={c.id} value={c.id}>{c.code} — {c.name}</option>)}</select></label>
      </div>
      {sourceType === "CONTRIBUTION" && <label>Payment method<select name="paymentMethod" required defaultValue=""><option value="">Select payment method</option>{["CASH","BANK_TRANSFER","CARD","MOBILE_MONEY","OTHER"].map(v => <option key={v} value={v}>{v.replaceAll("_"," ")}</option>)}</select></label>}
      <div className="field-pair">
        <label>{sourceType === "CONTRIBUTION" ? "Debit asset account" : "Debit expense account"}<select name="debitAccountId" required defaultValue=""><option value="">Select account</option>{accounts(debitType).map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <label>{sourceType === "CONTRIBUTION" ? "Credit income account" : "Credit payment account"}<select name="creditAccountId" required defaultValue=""><option value="">Select account</option>{accounts(creditType).map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
      </div>
      <p className="panel-description">Contribution: debit an asset account and credit an income account. Expenditure: debit an expense account and credit an asset account. All source records currently use ZAR.</p>
      <button className="primary-button" disabled={busy || !categories.some(c => c.active) || accounts(debitType).length === 0 || accounts(creditType).length === 0}>{busy ? "Saving…" : "Save source mapping"}</button>
    </form>
    <div className="table-wrap"><table><thead><tr><th>SOURCE</th><th>CATEGORY</th><th>PAYMENT METHOD</th><th>DEBIT ACCOUNT</th><th>CREDIT ACCOUNT</th><th>CURRENCY</th></tr></thead><tbody>
      {mappings.map(m => <tr key={m.id}><td>{m.sourceType}</td><td><strong>{m.categoryCode}</strong><small className="table-subtext">{m.categoryName}</small></td><td>{m.paymentMethod?.replaceAll("_"," ") ?? "—"}</td><td>{m.debitAccountCode} — {m.debitAccountName}</td><td>{m.creditAccountCode} — {m.creditAccountName}</td><td>{m.currency}</td></tr>)}
      {!mappings.length && <tr><td colSpan={6} className="empty-table">No contribution or expenditure account mappings configured.</td></tr>}
    </tbody></table></div>
  </section>;
}
