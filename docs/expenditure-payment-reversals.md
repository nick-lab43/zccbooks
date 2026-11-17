# Expenditure payment reversals

## Purpose

A paid expenditure is never edited or deleted to correct a payment. The reversal is recorded as a separate immutable operational event, linked to the original expenditure.

## Endpoint

`POST /api/expenditures/{expenditureId}/reverse-payment`

Request:

```json
{
  "reason": "Payment was made to the wrong supplier"
}
```

The caller must hold `FINANCE_REVERSE` for the expenditure's organisational unit. The endpoint only accepts records in `PAID` status and permits one reversal per expenditure.

## Accounting treatment

- The original expenditure, payment reference and payment journal remain unchanged.
- If the original payment has a linked journal in `POSTED` status, a balanced reversing journal is created by swapping every original debit and credit line.
- The reversing journal is created as a draft. It is not automatically submitted, approved or posted; existing independent journal controls remain in force.
- If the original payment journal exists but is not posted, the reversal is rejected and the transaction rolls back. Complete or reject the original journal workflow first.
- If no payment journal exists because the payment was recorded before a ledger mapping was configured, the operational reversal is still recorded without fabricating accounting entries.
- The reversal event and reversing journal use stable source linkage and database uniqueness constraints to prevent duplicates.

## Audit and history

The immutable `expenditure_payment_reversals` record stores the expenditure, reason, actor and timestamp. The `EXPENDITURE_PAYMENT_REVERSED` audit event records the reversal outcome. The original expenditure remains `PAID` as a historical fact; consumers should use the reversal record to determine that the payment has subsequently been reversed.
