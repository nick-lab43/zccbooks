# Deposit Management and Bank Reconciliation

## Scope

V13 adds controlled cash-deposit batching and a bank statement matching workflow. It builds on contribution capture and receipt issuance in V12.

## Permissions

- FINANCE_DEPOSIT: create draft batches, allocate cash receipts, remove draft allocations and submit batches within assigned geographical scope.
- FINANCE_RECONCILE: record bank statement entries and reconcile submitted batches. The V13 migration grants this to the seeded CENTRAL_ADMIN role only.
- FINANCE_VIEW: view batches and receipt allocations within the assigned scope.
- All API actions re-check the caller's permission against the batch or selected geographical unit.

## Deposit lifecycle

1. Create a draft batch with the scope, deposit date, bank name, bank reference and declared amount.
2. Allocate unallocated CASH contribution receipts from the same organisation and exact geographical unit.
3. Every receipt is allocated in full. A contribution can appear in only one deposit batch.
4. Submit only when at least one receipt is allocated and the sum of allocations equals the declared deposit amount.
5. Submitted batches are locked against allocation changes.

Electronic/card/mobile-money receipts are not included in cash deposit batches. They remain in the contribution ledger and require separate settlement/reconciliation controls if the organisation later chooses to track those payment rails.

## Bank statement matching

A user with FINANCE_RECONCILE records a bank statement entry for the same geographical unit. A submitted batch can be reconciled only when:
- The statement entry belongs to the same organisation and geographical unit.
- The bank reference matches exactly (case-insensitive).
- The amount matches exactly to the cent.
- The statement transaction date is within three calendar days of the deposit date.
- Neither the statement entry nor the batch has already been reconciled.

Successful reconciliation creates a one-to-one immutable reconciliation record, marks the batch RECONCILED, resolves follow-ups for receipts in that batch and writes an administrator audit event.

## Data model

- deposit_batches: declared deposit, status, scope and creator.
- deposit_allocations: one-to-one receipt allocation into a batch.
- bank_statement_entries: manually recorded statement transaction rows.
- deposit_reconciliations: immutable one-to-one match between a submitted batch and a statement entry.

## Endpoints

- GET/POST /api/organisations/{organisationId}/deposit-batches
- POST /api/deposit-batches/{batchId}/allocations
- GET /api/deposit-batches/{batchId}/allocations
- DELETE /api/deposit-batches/{batchId}/allocations/{contributionId}
- POST /api/deposit-batches/{batchId}/submit
- GET/POST /api/organisations/{organisationId}/bank-statement-entries
- POST /api/deposit-batches/{batchId}/reconcile
- GET /api/organisations/{organisationId}/unallocated-cash-contributions

## Limits and next controls

This phase records statement lines manually; it does not upload or parse bank files, connect to bank APIs, or perform automated matching. It does not yet handle partial deposits, split receipts, bank fees, cash shortages/overages, reversals, refunds, or electronic settlement batches. Differences are rejected rather than silently adjusted. A later exception workflow should preserve the original bank evidence and require a separately authorised resolution.

Hosted PostgreSQL verification is required for V13 migration, unique allocation constraints, simultaneous receipt allocation, duplicate statement entries, batch submission totals, scope enforcement and reconciliation locking. Do not use the system for live funds until those controls have been tested against the deployed database and operating procedures.
