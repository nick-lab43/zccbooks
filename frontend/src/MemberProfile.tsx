import { useEffect, useState } from "react";
import type { FormEvent } from "react";

type Profile = {
  id: string;
  firstName: string;
  lastName: string;
  zccAppNumber: string | null;
  phoneNumber: string | null;
  email: string | null;
  dateOfBirth: string | null;
};

export default function MemberProfile({ memberId, authorizationHeader }: {
  memberId: string;
  authorizationHeader: string;
}) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const apiBase = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "");

  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${apiBase}${path}`, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(authorizationHeader ? { Authorization: authorizationHeader } : {}),
        ...options?.headers
      }
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new Error(problem?.detail ?? `Request failed (${response.status}).`);
    }
    return response.json() as Promise<T>;
  }

  useEffect(() => {
    setProfile(null);
    setError("");
    setNotice("");
    if (!memberId) return;
    request<Profile>(`/api/members/${memberId}/profile`)
      .then(setProfile)
      .catch((e: Error) => setError(e.message));
  }, [memberId, authorizationHeader]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!profile) return;
    const form = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await request<Profile>(`/api/members/${memberId}/profile`, {
        method: "PATCH",
        body: JSON.stringify({
          firstName: form.get("firstName"),
          lastName: form.get("lastName"),
          phoneNumber: form.get("phoneNumber"),
          email: form.get("email"),
          dateOfBirth: form.get("dateOfBirth") || null
        })
      });
      setProfile(updated);
      setNotice("Member profile saved.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Profile could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  if (!memberId) return <p className="empty-note">Select a member to view or update their profile.</p>;
  if (error && !profile) return <div className="message error" role="alert">{error}</div>;
  if (!profile) return <p className="empty-note">Loading member profile…</p>;

  return (
    <section className="panel">
      <div className="panel-title"><div><span className="panel-index">PROFILE</span><h2>Member details</h2></div><span className="panel-tag">{profile.zccAppNumber || "NO APP NUMBER"}</span></div>
      <form onSubmit={save} className="form-stack">
        <div className="field-pair">
          <label>First name<input name="firstName" required maxLength={100} defaultValue={profile.firstName} /></label>
          <label>Last name<input name="lastName" required maxLength={100} defaultValue={profile.lastName} /></label>
        </div>
        <label>ZCC app number<input value={profile.zccAppNumber ?? ""} readOnly /></label>
        <div className="field-pair">
          <label>Mobile number<input name="phoneNumber" type="tel" maxLength={30} defaultValue={profile.phoneNumber ?? ""} /></label>
          <label>Email address<input name="email" type="email" maxLength={254} defaultValue={profile.email ?? ""} /></label>
        </div>
        <label>Date of birth<input name="dateOfBirth" type="date" max={new Date().toISOString().slice(0, 10)} defaultValue={profile.dateOfBirth ?? ""} /></label>
        <button className="primary-button" disabled={busy}>{busy ? "SAVING…" : "SAVE MEMBER PROFILE"}</button>
      </form>
      {error && <div className="message error" role="alert">{error}</div>}
      {notice && <div className="message success" role="status">{notice}</div>}
    </section>
  );
}
