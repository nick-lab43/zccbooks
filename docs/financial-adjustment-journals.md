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

A journal can only be approved by an administrator other than its creator. Posting requires a different administrator from the approver. The API locks the journal row while applying lifecycle transitions to prevent concurrent transitions.

Journal listing is restricted to the caller's visible unit hierarchy unless they have organisation-wide or global permission. Journal detail and all lifecycle operations enforce access against the journal's actual scope unit.

## Source references

Manual journals use source type MANUAL and no source ID. A journal linked to a reconciliation exception must identify an existing exception in the same organisation and geographical scope. The source reference is traceability metadata only; creating a journal does not resolve the exception.

## Audit and immutability

Account creation and updates, journal creation, line replacement, submission, approval, rejection and posting create administrator audit events.

V31 prevents business-header edits after submission and restricts submission, approval and posting metadata to their corresponding lifecycle transition. Database triggers prevent journal-line edits after draft, prevent deletion of non-draft journals, enforce legal status transitions, require separate creator/approver/poster identities and recheck balanced lines at posting. Posted journals cannot be updated or deleted.

## Accounting boundaries

This API does not automatically create journals from exception classifications, change contribution receipts or reversals, modify deposit allocations or bank statement entries, or change existing financial reports. A journal is an independently approved accounting record. Existing source records remain unchanged.
