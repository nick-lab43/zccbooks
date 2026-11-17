# Contribution and expenditure ledger account mappings

## Purpose

This release introduces organisation-specific account mappings as preparation for controlled source-to-ledger integration. It does not generate journals or change any operational financial workflow.

## Contribution mappings

Each mapping is defined by:
- Contribution category
- Payment method
- Debit asset account
- Credit income account
- Currency

The payment method is part of the key because cash receipts, bank transfers, card receipts and other payment channels may settle into different asset or clearing accounts.

## Expenditure mappings

Each mapping is defined by:
- Expenditure category
- Debit expense account
- Credit asset account
- Currency

## Validation and access

Mapping configuration requires `FINANCE_JOURNAL_CREATE`; reading mapping options and saved mappings requires `FINANCE_JOURNAL_VIEW`.

The API validates that the category belongs to the organisation and is active, both accounts belong to the same organisation, are active, and use the same currency. Contribution mappings require an ASSET debit and INCOME credit. Expenditure mappings require an EXPENSE debit and ASSET credit.

Contribution and expenditure source records currently use ZAR, so this release only permits ZAR mappings.

## No automatic posting in this release

Saving a mapping does not create a journal, change a contribution or expenditure status, settle a deposit, or affect a ledger balance.

Subsequent source integration must define distinct accounting events:
- Contribution receipt recognition, with the asset account determined by payment method.
- Deposit settlement as a transfer between cash/clearing and bank accounts, not a second income recognition.
- Expenditure payment recognition, without duplicating the expense when the payment is recorded.
- Reversals and refunds through explicit reversing entries linked to their original source records.

Every generated journal must have a stable source identity and database-enforced idempotency. It must remain a draft until submitted, independently approved and posted through the existing journal lifecycle.

## Contribution receipt journal generation

When a contribution receipt is captured, the application looks up the organisation's mapping for the receipt's category, payment method and currency. If a complete mapping exists and both mapped accounts remain active, it creates a balanced two-line journal in `DRAFT` status:

- Debit the mapped asset account.
- Credit the mapped income account.

The journal uses the receipt date, amount, currency and organisational scope. It is linked to the contribution using source type `CONTRIBUTION_RECEIPT` and the contribution ID. A database unique index enforces at most one journal per receipt. Repeated capture requests with the same idempotency key reuse the receipt and safely re-check journal generation.

A missing mapping does not prevent receipt capture. The application records a `CONTRIBUTION_LEDGER_MAPPING_MISSING` audit event and does not create an incomplete journal. Replaying the idempotent capture request after configuring the mapping retries journal generation.

Generated journals are never submitted, approved or posted automatically. They use the existing independent journal lifecycle. A contribution with a linked journal cannot be reversed through the receipt reversal endpoint until the separate reversing-journal workflow is available; this prevents a ledger-recognised receipt from being reversed without an accounting counterpart.

This generation step recognises the contribution receipt. Cash deposit settlement remains a separate asset-to-asset transfer and must not recognise income a second time.

## Deposit settlement journal generation

Each verified bank-credit reconciliation is a separate settlement event. When the reconciliation is recorded, the application looks up the organisation's mapping by the deposit batch's bank name. The mapping selects two active ZAR asset accounts:

- Debit the bank asset account for the matched statement credit.
- Credit the cash-on-hand asset account from which the deposit was made.

The journal uses the bank statement transaction date, the reconciled amount, the deposit batch's organisational scope and the administrator who performed the reconciliation. Its source type is `DEPOSIT_SETTLEMENT` and its source ID is the reconciliation ID. A database unique index prevents more than one settlement journal per reconciliation. Partial bankings create separate journals for each verified statement credit; their sum follows the deposit batch's reconciliation total.

If a mapping is missing, bank reconciliation continues and the application records a `DEPOSIT_SETTLEMENT_LEDGER_MAPPING_MISSING` audit event. Saving the bank mapping retries journal generation for earlier reconciliations for that bank which do not yet have a settlement journal.

Generated settlement journals remain `DRAFT` and require the existing independent submission, approval and posting workflow. The entry is an asset-to-asset transfer only: it does not debit or credit income and does not recognise contribution receipts a second time.
