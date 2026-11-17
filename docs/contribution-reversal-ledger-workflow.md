# Contribution reversal and ledger workflow

## Purpose

A contribution receipt is an immutable operational record. Reversing a receipt does not delete or alter the receipt, and a posted ledger journal is never edited.

## Reversal behaviour

- The reversal endpoint requires the existing `FINANCE_REVERSE` permission and a non-empty reason.
- A receipt allocated to a deposit batch remains ineligible for reversal until the deposit workflow is resolved.
- If no contribution journal exists because the organisation had no active account mapping when the receipt was captured, the immutable receipt reversal is recorded without a ledger journal.
- If a source journal exists, it must already be `POSTED`. A draft, submitted, approved-but-unposted, or rejected source journal blocks the receipt reversal. This prevents a pending original journal from being posted after its receipt has been reversed.
- For a posted source journal, the system creates a balanced reversing journal by swapping every original debit and credit line. Account, currency, organisation and financial scope are retained.
- The reversing journal is linked to the immutable contribution reversal record through source type `CONTRIBUTION_REVERSAL` and source ID equal to the reversal ID.
- A database unique index permits at most one reversing journal per reversal record.
- The reversing journal remains `DRAFT`. It must pass the existing independent submit, approve and post workflow before the ledger reflects the reversal.
- The original posted journal and its lines remain unchanged.

## Transaction and audit controls

The immutable contribution reversal, reversing draft journal and audit events are created in one database transaction. If journal creation fails, the receipt reversal is rolled back as well. Reversal attempts are protected by the contribution row lock and the existing unique contribution-reversal constraint.

## Operational limitation

This phase handles contribution receipt reversals only. Deposit settlement reversals and paid expenditure corrections require their own explicit operational reversal events and are not implied by this workflow.
