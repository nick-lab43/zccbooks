# Financial accounts and journal lifecycle API

## Overview

V30 introduced the chart-of-accounts and double-entry journal schema. V31 hardens submitted journal headers and lifecycle metadata. The API in `FinancialJournalController` provides account administration and the controlled journal workflow.

## Permissions

- `FINANCE_JOURNAL_VIEW`: list accounts and journals, and view journal detail.
- `FINANCE_JOURNAL_CREATE`: create accounts, create draft journals, edit draft lines and submit journals.
- `FINANCE_JOURNAL_APPROVE`: approve or reject submitted journals and post approved journals.

Permissions are checked against the organisation or journal's geographical unit. Unit-scoped assignments inherit access to descendant units through the existing authorization service. Account catalogue access is organisation-wide within the permission scope because accounts are shared by journals in that organisation.

The V30 migration does not grant these permissions to existing roles. Assign them deliberately through the existing role administration process.

## Account endpoints

- `GET /api/organisations/{organisationId}/finance-accounts`
- `POST /api/organisations/{organisationId}/finance-accounts`
- `PATCH /api/finance-accounts/{accountId}`

Account codes are unique case-insensitively within an organisation. Account type is one of ASSET, LIABILITY, EQUITY, INCOME or EXPENSE. Currency is a three-letter code. Account code, type and currency are immutable after creation. Account names cannot be changed after an account has been used by a journal line. Accounts can be deactivated and reactivated; inactive accounts cannot be added to or submitted in journals.

## Journal endpoints

- `GET /api/organisations/{organisationId}/finance-journals?status={status}`
- `POST /api/organisations/{organisationId}/finance-journals`
- `GET /api/finance-journals/{journalId}`
- `PUT /api/finance-journals/{journalId}/lines`
- `POST /api/finance-journals/{journalId}/submit`
- `POST /api/finance-journals/{journalId}/approve`
- `POST /api/finance-journals/{journalId}/reject`
- `POST /api/finance-journals/{journalId}/post`

Journal creation includes its initial lines. A line must contain exactly one positive debit or credit, and must reference an active account belonging to the same organisation and currency. Draft lines can be replaced as a set. Submission requires at least two lines and equal positive debit and credit totals.

A journal must have three distinct administrators: the creator, the approver and the poster. The API locks the journal row while applying lifecycle transitions to prevent concurrent transitions.

Journal listing is restricted to the caller's visible unit hierarchy unless they have organisation-wide or global permission. Journal detail and all lifecycle operations enforce access against the journal's actual scope unit.

## Source references

Manual journals use source type MANUAL and no source ID. A journal linked to a reconciliation exception must identify an existing exception in the same organisation and geographical scope. The source reference is traceability metadata only; creating a journal does not resolve the exception.

## Reconciliation exception account mappings

Organisations can configure one debit and one credit account for each supported financial exception classification and currency. Mappings are organisation-specific; both accounts must be active, belong to the same organisation and currency, and must be different accounts.

Supported journal-generating classifications are BANK_FEE, SHORT_DEPOSIT, OVER_DEPOSIT and REFUND_OR_REVERSAL. Other classifications remain investigation dispositions and do not generate journal drafts. Exception disposition amounts are currently recorded in ZAR, so journal generation uses ZAR mappings only.

- `GET /api/organisations/{organisationId}/finance-exception-account-mappings`
- `PUT /api/organisations/{organisationId}/finance-exception-account-mappings`

A finance exception manager can classify an exception while moving it to UNDER_REVIEW. The classification requires a supporting note; financial classifications also require a positive amount. The exception remains under review while the adjustment is prepared.

- `POST /api/reconciliation-exceptions/{exceptionId}/journal`

This endpoint creates a DRAFT journal using the configured debit and credit accounts and links it to the source exception. It does not submit, approve or post the journal, and it does not resolve the exception. The normal three-person journal lifecycle remains mandatory. A unique database index prevents more than one journal from being linked to the same exception. Once the journal has been posted and supporting evidence checked, an authorised exception manager can resolve the exception through the existing exception workflow.

Changing a mapping affects future journal drafts only. Existing journals retain their original account lines.

## Audit and immutability

Account creation and updates, journal creation, line replacement, submission, approval, rejection and posting create administrator audit events.

V31 prevents business-header edits after submission and restricts submission, approval and posting metadata to their corresponding lifecycle transition. Database triggers prevent journal-line edits after draft, prevent deletion of non-draft journals, enforce legal status transitions, require three distinct creator/approver/poster identities and recheck balanced lines at posting. Posted journals cannot be updated or deleted.

## Accounting boundaries

The Accounts & Journals workspace exposes account administration, reconciliation account mappings, balanced two-line manual draft creation, journal filtering and journal detail. The UI shows lifecycle actions only to users with the corresponding permissions; the API remains authoritative for scope and separation-of-duties enforcement.

A reconciliation exception can create a linked DRAFT journal only through the explicit exception journal endpoint described above. Journal creation does not submit, approve or post the entry, resolve the exception, change contribution receipts or reversals, modify deposit allocations or bank statement entries, or alter existing financial reports. A journal is an independently approved accounting record. Existing source records remain unchanged.
