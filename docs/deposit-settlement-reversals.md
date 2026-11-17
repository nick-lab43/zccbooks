# Deposit Settlement Reversals

A bank reconciliation can be reversed when a statement credit was incorrectly matched or later returned. Reversal is append-only: the original reconciliation, bank statement entry and posted journal are retained.

## Workflow

- A central finance administrator with `FINANCE_REVERSE` permission in the deposit's unit scope submits a reason.
- The API locks the reconciliation and deposit batch, prevents duplicate reversals, and inserts an immutable reversal event.
- If a settlement journal exists, it must be POSTED. A pending or rejected journal blocks reversal until its workflow is resolved.
- A posted source journal produces a linked reversing journal with every debit and credit swapped. The reversing journal remains DRAFT and requires independent submission, approval and posting.
- If no journal exists because the mapping was absent at reconciliation time, the operational reversal is recorded without fabricating a ledger entry.
- The original bank statement entry remains consumed and cannot be matched to another batch.

## Balance effects

Reversed reconciliations remain in history but are excluded from effective banked totals. Batch status is recalculated as `SUBMITTED`, `PARTIALLY_RECONCILED`, or `RECONCILED`. Contribution deposit follow-ups reflect the remaining settlement state.

## Endpoint

`POST /api/deposit-reconciliations/{reconciliationId}/reverse`

Request body:

```json
{ "reason": "Incorrect statement credit matched to this deposit" }
```

The response includes the reversal, reconciliation and batch IDs, reversed amount, remaining banked amount, outstanding amount and recalculated status.

## Accounting controls

- A unique constraint allows one immutable reversal per reconciliation.
- A partial unique index allows one reversing journal per reversal event.
- Original posted journals and their lines are never changed.
- Reversing journals remain drafts; no automatic approval or posting occurs.
- Reversal, journal creation, batch recalculation and audit event share one transaction.
