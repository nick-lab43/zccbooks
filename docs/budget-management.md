# Expenditure Budget Management

## Purpose

Budget Management allocates authorised spending limits to expenditure categories and geographical units. The budget performance register compares each allocation with approved and paid expenditure recorded within the budget period.

## Authority

- `FINANCE_BUDGET_MANAGE` is the dedicated budget-management permission.
- The initial migration grants it only to `CENTRAL_ADMIN`.
- Creating and revising budgets requires both central administrator status and the global permission.
- Reading budget performance requires `FINANCE_VIEW` for the selected geographical scope.
- Custom roles can be granted the permission through the existing role configuration, but central-administrator status remains an additional requirement for writes.

## Budget allocation

Each allocation contains:
- Organisation and geographical unit.
- Expenditure category.
- Inclusive start and end dates.
- Budget amount and ISO currency code.
- Optional allocation note.

Amounts are not converted between currencies. Budgets for the same category and currency cannot overlap in time at the same unit or between ancestor/descendant units. This prevents the same spending from being governed by overlapping allocations at different levels of the geographical hierarchy.

## Budget performance

Actual expenditure includes only records with status `APPROVED` or `PAID`, with an expense date inside the budget period. It includes records at the budget unit and its descendants. Draft, submitted and rejected records are excluded.

The register displays:
- Approved budget amount.
- Actual expenditure.
- Remaining amount (which may be negative when overspent).
- Utilisation percentage (which may exceed 100%).

Actuals are grouped by the budget's category and currency. There is no foreign-exchange conversion or combined cross-currency total.

## Amendments and audit

Budgets are revised rather than silently overwritten. Every amendment:
- Requires a reason.
- Supplies the expected current version.
- Locks the budget row and rejects stale versions.
- Stores previous and revised amounts, version numbers, actor and timestamp in the immutable revision history.
- Creates an administrator audit event.

A revision changes the current budget amount; it does not rewrite the previous revision record.

## Not included

This release does not implement cash-flow forecasting, purchase commitments, supplier purchase orders, multi-stage budget approval, transfers between budget lines, automatic budget alerts, or bank-verified payment matching. The budget performance calculation is based on approved and paid expenditure records, not invoices or unapproved commitments.
