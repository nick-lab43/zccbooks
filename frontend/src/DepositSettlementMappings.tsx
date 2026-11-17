import { useEffect, useMemo, useState } from "react";

type Account = { id: string; code: string; name: string; active: boolean };
type Mapping = { id: string; bankName: string; bankAccountId: string; bankAccountCode: string; bankAccountName: string; cashAccountId: string; cashAccountCode: string; cashAccountName: string };
type Options = { accounts: Account[]; mappings: Mapping[] };

export default function DepositSettlementMappings({ organisationId, authorizationHeader }: {
  organisationId: string; authorizationHeader: string;
}) {
  const [options, setOptions] = useState<Options | null>(null);
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
    setOptions(await request<Options>(`/api/organisations/${organisationId}/finance-deposit-settlement-mappings`));
  }

  useEffect(() => {
    if (!organisationId) return;
    load().catch((e: Error) => setError(e.message));
  }, [organisationId, authorizationHeader]);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    try {
      await request<void>(`/api/organisations/${organisationId}/finance-deposit-settlement-mappings`, {
        method: "PUT",
        body: JSON.stringify({
          bankName: form.get("bankName"),
          bankAccountId: form.get("bankAccountId"),
          cashAccountId: form.get("cashAccountId")
        })
      });
      await load();
      setNotice("Deposit settlement mapping saved. Existing unreconciled ledger events were checked for journal generation.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Mapping could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  const accounts = (options?.accounts ?? []).filter(a => a.active);
  return <section className="journal-source-mappings">
    <div className="panel-divider" />
    <div className="journal-section-heading"><div><h3>Deposit settlement account mappings</h3><p>Map each bank to its bank asset account and the cash-on-hand asset account used before deposit. This records an asset transfer, not contribution income.</p></div><span>ASSET TRANSFER</span></div>
    {error && <div className="message error" role="alert">{error}</div>}
    {notice && <div className="message success" role="status">{notice}</div>}
    <form className="form-stack journal-form" onSubmit={save}>
      <label>Bank name<input name="bankName" required maxLength={120} placeholder="Use the same bank name entered on deposit batches" /></label>
      <div className="field-pair">
        <label>Debit bank account<select name="bankAccountId" required defaultValue=""><option value="">Select bank asset account</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
        <label>Credit cash account<select name="cashAccountId" required defaultValue=""><option value="">Select cash asset account</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
      </div>
      <p className="panel-description">Only active ZAR asset accounts are accepted. A verified reconciliation creates a DRAFT journal: debit bank, credit cash. It is not submitted, approved or posted automatically.</p>
      <button className="primary-button" disabled={busy || accounts.length < 2}>{busy ? "Saving…" : "Save deposit mapping"}</button>
    </form>
    <div className="table-wrap"><table><thead><tr><th>BANK</th><th>DEBIT BANK ACCOUNT</th><th>CREDIT CASH ACCOUNT</th><th>CURRENCY</th></tr></thead><tbody>
      {(options?.mappings ?? []).map(m => <tr key={m.id}><td>{m.bankName}</td><td>{m.bankAccountCode} — {m.bankAccountName}</td><td>{m.cashAccountCode} — {m.cashAccountName}</td><td>ZAR</td></tr>)}
      {!options?.mappings.length && <tr><td colSpan={4} className="empty-table">No deposit settlement mappings configured.</td></tr>}
    </tbody></table></div>
  </section>;
}
