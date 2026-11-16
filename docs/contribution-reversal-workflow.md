# Contribution reversal workflow

Posted contribution receipts are immutable. An authorised reversal creates a separate, append-only record; it never edits or deletes the original contribution or reuses its receipt number.

## Authority

The `FINANCE_REVERSE` permission is granted to the global Central Administrator role by default. It can be assigned to a custom role through the existing role configuration workflow. The backend also checks the administrator's geographical scope against the original contribution. Reversal authority is independent of `FINANCE_CAPTURE`: an administrator can be assigned reversal-only access without being allowed to post contributions.

## Contribution register

The financial administration workspace exposes a scoped register for administrators with `FINANCE_VIEW`:

`GET /api/organisations/{organisationId}/contribution-register?scopeUnitId={scopeUnitId}`

The register returns up to 200 most recent receipts in the selected scope, including whether each receipt has been reversed and the reversal reason, administrator and timestamp. The endpoint requires `FINANCE_REVERSE`. Reversal-only administrators receive a scope selector populated from their assigned reversal scopes and can access this register without `FINANCE_CAPTURE` or `FINANCE_VIEW`. The interface requires a reason and displays a permanent confirmation warning before submission. Capture-authorised administrators continue to use the same workspace; capture forms and deposit follow-ups are hidden when the administrator has reversal-only access.

## Reverse a contribution

`POST /api/contributions/{contributionId}/reverse`

Request:

```json
{
  "reason": "Receipt was issued against the wrong member."
}
```

A successful response contains the reversal ID, original receipt number and amount, reason, administrator and timestamp.

A contribution can be reversed only once. Reversal is rejected if the contribution has any deposit allocation, including an allocation in a draft or already submitted batch. The deposit workflow must be resolved before reversal; this prevents a receipt from being removed from income while remaining represented in a deposit package.

## Retrieve reversal details

`GET /api/contributions/{contributionId}/reversal`

Requires `FINANCE_VIEW` in the contribution's scope. Returns the immutable reversal record. A 404 means the contribution has not been reversed.

## Reporting

Reversed contributions are excluded from contribution registers, unallocated cash follow-ups, financial target progress, contribution summaries, contribution detail reports and income totals. The original receipt remains available for audit and reversal inspection.

## Integrity

- One reversal per contribution is enforced by a unique database constraint.
- The original contribution row is unchanged.
- Reversal reason, actor and timestamp are retained.
- Database triggers reject updates and deletes to reversal records.
- Every reversal writes an administrator audit event.
- A reversal cannot be made for a contribution already allocated to a deposit batch.

This workflow does not yet reverse a bank deposit or issue a cash refund. Those require separate, explicitly recorded financial events.
