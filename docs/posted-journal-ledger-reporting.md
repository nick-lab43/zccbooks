# Posted-journal general ledger reporting

## Scope

The General Ledger Reports workspace provides a trial balance and account statements using the organisation's double-entry financial journals.

## Trial balance

The trial balance reports each chart-of-accounts entry's cumulative debit and credit activity, plus its remaining debit-side or credit-side balance, as at the selected date. It includes journals dated on or before that date and whose status is `POSTED`.

## Account statement

An account statement shows:
- Opening debit or credit balance from posted journal lines dated before the selected period.
- Posted journal entries for the selected account and period.
- Period debit and credit totals.
- Closing debit or credit balance after period activity.

Entries are ordered by journal date and journal number. The account currency is retained throughout; the system does not combine balances from different currencies.

## Scope and controls

Both reports require `FINANCE_JOURNAL_VIEW`. The selected organisational unit and its descendants define the financial scope. Unit-level users only see units they are authorised to access. The API validates the organisation and selected unit independently of the frontend.

Only posted journals contribute to ledger totals. Draft, submitted, approved-but-unposted and rejected journals are excluded. Posted journals and their lines remain immutable under the existing database controls.

Report generation is recorded in the administrator audit log. The report endpoints do not post journals, modify source contributions or expenses, or create accounting entries.

## Accounting boundary

Contribution and expenditure registers remain operational source-record reports. The general ledger is a separate posted-journal view. This release does not automatically recognise contributions, deposits or expenditures in the general ledger; doing so requires separately designed source-to-ledger posting rules to prevent double recognition.
