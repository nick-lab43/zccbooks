export default function App() {
  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand">ZCC <span>BOOKS</span></div>
        <div className="context">CHURCH FINANCE &amp; AUDIT MANAGEMENT</div>
      </header>
      <section className="page-heading">
        <p className="eyebrow">SYSTEM FOUNDATION</p>
        <h1>Financial records, clearly accounted for.</h1>
        <p className="intro">
          A central workspace for branch membership, contributions, receipts,
          deposits, expenditure and audit reconciliation.
        </p>
      </section>
      <section className="notice" aria-live="polite">
        <strong>Foundation build</strong>
        <p>
          The application shell is in place. Branch operations and financial
          workflows will be delivered in subsequent milestones.
        </p>
      </section>
      <footer> ZCC Books · Initial development environment </footer>
    </main>
  );
}