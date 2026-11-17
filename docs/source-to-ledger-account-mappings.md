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
