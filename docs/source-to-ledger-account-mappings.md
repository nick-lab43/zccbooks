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
