# Bank statement imports and reconciliation exceptions

## Purpose

This phase adds a controlled import path for bank statement CSV files and an exception register for transactions that cannot be reconciled cleanly. It extends deposit batching and manual reconciliation; it does not replace the reconciliation approval step.

## CSV format

The first row is a header. Required columns:

- `date`: transaction date in ISO format, `YYYY-MM-DD`
- `reference`: bank transaction reference (maximum 120 characters)
- `amount`: non-zero decimal amount; positive values are credits and negative values are debits

Optional columns:

- `description`: bank-provided transaction description
- `type`: `CREDIT` or `DEBIT`; when supplied it overrides the sign-derived type

Quoted CSV fields and escaped double quotes are supported. Files are limited to 5 MB and 10,000 transaction rows. Each import is tied to one organisation and one financial scope. The SHA-256 fingerprint prevents importing the exact same file twice in the same scope. Rows that already exist by scope, date, reference and absolute amount are counted as duplicates and skipped.

The CSV parser expects one transaction per logical row. Dates must be ISO formatted; bank-specific formats must be normalised before upload.

## Exception detection

A Central Administrator with `FINANCE_EXCEPTION` can run a scope-specific scan. The scan creates idempotent exceptions for:

- Submitted deposit batches with no bank credit matching reference, amount and the existing three-day date window.
- Submitted deposit batches where the reference exists but the amount differs.
- Bank credits with no submitted deposit batch, or with a reference that cannot be matched to a submitted deposit.
- Bank debits requiring classification, such as bank fees or other withdrawals.

The scan does not automatically reconcile deposits, alter contribution receipts, classify debits or resolve exceptions. Exact candidate matches remain available for the existing manual reconciliation action. Exception records are source-linked and unique by source and exception type.

## Resolution controls

- Exception visibility uses the existing scoped `FINANCE_VIEW` permission.
- CSV import uses `FINANCE_RECONCILE`.
- Scanning and resolving exceptions use `FINANCE_EXCEPTION`; the migration grants this to the seeded `CENTRAL_ADMIN` role only.
- Exceptions move from `OPEN` to `UNDER_REVIEW` or `RESOLVED`.
- A resolution note is mandatory when resolving.
- Resolved exceptions cannot be edited or reopened through this API.
- Import, scan and resolution actions write administrator audit events.

Custom roles may be granted `FINANCE_EXCEPTION` by an authorised system administrator. Keep investigation and resolution authority separate from routine contribution capture.

## API

- `POST /api/organisations/{organisationId}/bank-statement-imports?scopeUnitId={scopeUnitId}` — multipart CSV import; field name `file`.
- `POST /api/organisations/{organisationId}/reconciliation-exceptions/scan?scopeUnitId={scopeUnitId}` — detect exceptions.
- `GET /api/organisations/{organisationId}/reconciliation-exceptions?scopeUnitId={scopeUnitId}&includeResolved=false` — list exceptions.
- `PATCH /api/reconciliation-exceptions/{exceptionId}` — set `UNDER_REVIEW` or `RESOLVED` and supply a resolution note.\n- `GET /api/reconciliation-exceptions/{exceptionId}/history` — read the immutable status-transition history within the exception's scope.

## Operational limitations

- CSV import is generic and not yet tailored to individual bank export formats.
- No direct bank API or scheduled statement retrieval exists.
- Duplicate detection is exact-file fingerprint plus transaction identity; it does not perform fuzzy duplicate detection.
- A scan identifies exception candidates but does not infer a resolution or post an adjustment.
- Bank debits are surfaced for classification; they are not automatically treated as fees or deducted from contribution totals.
- No partial deposits, split deposits, chargebacks, refunds, reversals or automated write-offs are supported.
- Exception scanning is repeatable and avoids duplicate exception rows, but it does not automatically close exceptions when a later transaction is reconciled. Resolved exceptions remain immutable in this workflow.
- Hosted PostgreSQL migration and end-to-end verification are required before operational use. Continue using fictional demonstration records until that verification is complete.


## Immutable exception lifecycle history

Every newly detected exception receives an initial history entry. Moving an exception to review or resolving it appends a separate status-transition record containing the previous status, new status, operator, timestamp and any supplied note. The history table rejects updates and deletes at the database level. The exception's current status remains available in the register, while the history endpoint exposes the complete sequence to users with finance-view access.

Existing exceptions are backfilled with a baseline snapshot of their status and last recorded update time. Where the older record did not retain an actor, the baseline explicitly reports that the actor was not recorded; it does not infer or fabricate one. This history records exception workflow decisions and does not itself establish that the underlying bank difference was financially corrected.


## Structured exception dispositions

When an authorised finance exception manager resolves an exception, the operator must select a classification and provide a narrative note. Supported classifications are bank fee, short deposit, over-deposit, wrong reference, duplicate statement entry, non-deposit transaction, refund/reversal and other. A positive ZAR amount is mandatory for bank fees, short deposits, over-deposits and refunds/reversals. The selected disposition and amount are stored on the exception and copied into the append-only status history. Existing resolved exceptions remain readable as legacy records without a structured disposition.

These fields document the operator's classification; they do not post a journal, change contribution income, alter a deposit's declared amount, or reconcile a bank entry. A fee or variance must still be handled through an independently approved accounting process. The exception cannot be resolved by merely entering a number: the note must explain the supporting evidence and action taken.
