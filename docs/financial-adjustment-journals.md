# Financial adjustment journal foundation

## Purpose

Migration V30 establishes the database foundation for controlled double-entry financial adjustments. It is the next accounting layer after reconciliation exception dispositions: an exception explains a discrepancy; a journal records an independently approved accounting adjustment.

This phase is schema-only. It does not expose journal endpoints, create account setup screens, automatically convert reconciliation exceptions into journals, or change existing contribution and expenditure reports.

## Account catalogue

Each organisation can maintain accounts identified by a case-insensitive unique code. Accounts have one of five types: asset, liability, equity, income or expense. Each account has one currency. This avoids silently combining currencies in one account or journal.

## Journal model

A journal belongs to an organisation and geographical scope and records a journal number, effective date, currency, description and optional source reference. Its lifecycle is:

- DRAFT — editable lines can be recorded.
- SUBMITTED — awaiting independent review.
- APPROVED — approved by an administrator other than the creator.
- POSTED — immutable accounting record.
- REJECTED — retained as a rejected submission.

Journal lines contain exactly one positive debit or credit amount. Each line must reference an account in the same organisation and currency as the line. Database enforcement prevents line edits outside DRAFT.

Posting is permitted only from APPROVED. The database rejects a posted journal unless it has at least two lines and equal positive debit and credit totals in the journal currency. Posted journals cannot be updated or deleted. Corrections must therefore use a separate compensating journal; they must not rewrite posted history.

## Permissions

V30 introduces separate permissions:

- FINANCE_JOURNAL_VIEW
- FINANCE_JOURNAL_CREATE
- FINANCE_JOURNAL_APPROVE

The migration does not grant these permissions to existing roles. Role assignment and scoped API enforcement will be implemented with the journal workflow endpoints, rather than implicitly expanding existing finance permissions.

## Accounting boundaries

- Journal entries do not modify contribution receipts, contribution reversals, deposit allocations, bank statement entries or expenditure records.
- Reconciliation exception dispositions remain investigation metadata. They are not accounting entries.
- No automatic account mapping is inferred for bank fees, shortages, overages, refunds or reversals.
- Existing income and expenditure reports continue to use their current source records until a separately reviewed reporting migration defines how posted ledger balances are presented.
- Each journal is single-currency. Foreign exchange and currency revaluation are outside this phase.

## Next phase

Implement account administration and the journal lifecycle API with strict organisation/unit scope checks, independent approval, posting identity, append-only audit events, optimistic concurrency, and tests against PostgreSQL. Only after that workflow is verified should specific bank reconciliation exception types be linked to journal templates.
