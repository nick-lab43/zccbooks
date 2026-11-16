# Expenditure Budget Performance Reporting

The Financial Reports workspace includes an **Expenditure budget performance** report and Word export.

## Scope and period rules

- The selected geographical scope includes the selected unit and descendants.
- A budget line is included only when its complete budget period falls within the selected report dates. This avoids comparing a full-period budget with a partial-period actual amount.
- Actual expenditure is calculated across the budget unit and descendants, for the full budget period.
- Only `APPROVED` and `PAID` expenditure is counted.
- Draft, submitted and rejected expenditure is excluded.
- Amounts are reported separately by currency; no exchange-rate conversion or cross-currency total is produced.

## Report fields

Each budget line contains its period, unit, category, allocated budget, actual expenditure, remaining amount and utilisation percentage. Remaining amount is budget less actual and may be negative. Utilisation may exceed 100%.

The Word export uses the same scoped query and includes the standard report context and audit event.

## Important interpretation

The report intentionally excludes budget periods that only partially overlap the selected dates. To report a monthly budget, select that complete month; to report an annual allocation, select the full annual period. The report does not prorate budget allocations.
