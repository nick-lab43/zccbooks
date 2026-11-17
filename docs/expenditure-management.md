# Expenditure Management

## Purpose

The expenditure workflow records church spending by organisation and geographical unit. It separates capture, approval and payment recording so that the person who enters an expense cannot approve their own submission.

## Permissions

- FINANCE_EXPENSE_CAPTURE: view expenditure categories and permitted units, create draft expenses and submit them for review.
- FINANCE_EXPENSE_APPROVE: review submitted expenses and record payment references.
- Category creation and maintenance require the global FINANCE_EXPENSE_APPROVE permission. Categories are organisation-specific.
- All expense actions check the target unit against the administrator's assigned geographical scope.

Migration V17 grants capture to CENTRAL_ADMIN, NYIKA_ADMIN and FINANCIAL_OFFICER. Approval is initially restricted to CENTRAL_ADMIN. Custom roles can be configured through the existing role-permission administration.

## Workflow

1. A permitted administrator records a draft with date, category, payee, description, optional invoice/reference, amount and currency.
2. The creator submits the draft. Submitted records are locked against editing.
3. A draft can be amended only by its original capturer. Each amendment increments a version; stale edits are rejected.
4. The original capturer can withdraw a draft with a mandatory reason. The record remains visible as VOIDED and is retained for audit.
5. An administrator with approval permission reviews the submission.
6. The creator cannot approve or reject their own expense. Rejection requires a reason.
7. An approved expense can be marked paid by an authorised approver. A payment reference is mandatory. The paid transition attempts to create a balanced draft ledger journal when the organisation has an active mapping for the expenditure category.
8. Rejected and withdrawn expenses remain in the register for audit purposes. Submitted or reviewed expenses cannot be withdrawn; they must follow the review process.

The workflow records approval and payment references. It does not yet verify payment against a bank statement or payment provider. A recorded payment is the source event for expenditure ledger recognition; approval alone never creates a journal.

## Financial reporting

- Expenditure register: lists expenses by selected date range and geographical scope, including workflow status.
- Income and expenditure summary: compares captured contribution income with APPROVED and PAID expenditure.
- Totals remain separated by currency. No foreign-exchange conversion or implied consolidated total is performed.
- Draft, submitted and rejected expenses are excluded from the income-and-expenditure calculation.
- Both report types can be previewed and exported to Word.

## Controls

- Organisation and geographical unit ownership are enforced by database foreign keys and API validation.
- Unit permissions are checked on every read and state-changing request.
- Draft amendments, withdrawal, review and payment transitions lock the expense row before checking its current status.
- Draft amendments use optimistic version checks and are limited to the original capturer.
- Withdrawal is permitted only before submission, requires a reason, and preserves the original record with VOIDED status.
- Approval requires a different administrator from the original capturer.
- Category codes are unique within an organisation.
- Every category and expense lifecycle action is written to the append-only administrator audit log.

## Not yet included

- Budget allocation and budget-versus-actual controls.
- Recurring expenses, purchase orders and supplier account management.
- Attachments for invoices, receipts or proof of payment.
- Bank-statement matching for expense payments. The generated expenditure journal is based on the authorised PAID transition and is not independently bank-verified.
- Multi-stage approval thresholds, approval delegation and reversal/refund workflows.
- Automated tax/VAT handling or foreign-exchange conversion.

Do not treat a PAID status as independently bank-verified. It means an authorised administrator recorded the payment reference. Hosted PostgreSQL tests and production operational controls must pass before live financial use.
