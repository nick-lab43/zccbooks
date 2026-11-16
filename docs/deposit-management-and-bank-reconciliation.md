# Deposit Management and Bank Reconciliation

## Scope

The deposit workflow groups cash contribution receipts into controlled batches and reconciles those batches against bank statement credits. V16 extends the original one-credit reconciliation model to support partial banking and multiple credits against one batch.

## Permissions

- FINANCE_DEPOSIT: create draft batches, allocate cash receipts, remove draft allocations and submit batches within assigned geographical scope.
- FINANCE_RECONCILE: record bank statement entries and reconcile submitted batches. The seeded CENTRAL_ADMIN role receives this permission.
- FINANCE_VIEW: view batches and receipt allocations within the assigned scope.
- All API actions re-check the caller's permission against the batch or selected geographical unit.

## Deposit lifecycle

1. Create a draft batch with the scope, deposit date, bank name, bank reference and declared amount.
2. Allocate unallocated CASH contribution receipts from the same organisation and exact geographical unit.
3. Every receipt is allocated in full. A contribution can appear in only one deposit batch.
4. Submit only when at least one receipt is allocated and the sum of allocations equals the declared deposit amount.
5. Submitted batches are locked against allocation changes.

Electronic/card/mobile-money receipts are not included in cash deposit batches. They remain in the contribution ledger and require separate settlement/reconciliation controls if the organisation chooses to track those payment rails.

## Partial banking and reconciliation

A submitted batch may be reconciled against one or more bank statement CREDIT entries. Each applied credit must:

- Belong to the same organisation and geographical unit as the batch.
- Match the batch bank reference (case-insensitive).
- Have a transaction date within three calendar days of the recorded deposit date.
- Be an unused statement entry.
- Not exceed the remaining outstanding balance.

A partial credit changes the batch status to PARTIALLY_RECONCILED. The reconciliation request must include an explanation of why the full amount was not banked. The explanation is stored with the immutable reconciliation record and surfaced while a balance remains outstanding.

Further eligible credits can be applied to the same batch. The system calculates the cumulative banked amount and outstanding balance from the linked statement entries. The batch becomes RECONCILED only when the cumulative credits equal the declared amount.

Contribution follow-ups move automatically with the batch: IN_DRAFT after allocation, SUBMITTED after submission, PARTIALLY_BANKED after a partial credit, and RESOLVED only after full reconciliation. Removing an allocation from a draft batch returns its follow-up to PENDING. Manual resolution is restricted to unallocated receipts and requires a reason.

Over-banking, debit entries, cross-scope entries, mismatched references, out-of-window dates and reuse of a statement entry are rejected. A statement entry can be applied to only one batch.

## Data model

- deposit_batches: declared deposit, status, scope and creator.
- deposit_allocations: one-to-one receipt allocation into a batch.
- bank_statement_entries: manually recorded or imported statement transaction rows, including CREDIT/DEBIT type.
- deposit_reconciliations: immutable records linking each statement entry to a batch. A batch can have multiple reconciliation records; a statement entry can be used once.

## Endpoints

- GET/POST /api/organisations/{organisationId}/deposit-batches
- POST /api/deposit-batches/{batchId}/allocations
- GET /api/deposit-batches/{batchId}/allocations
- DELETE /api/deposit-batches/{batchId}/allocations/{contributionId}
- POST /api/deposit-batches/{batchId}/submit
- GET/POST /api/organisations/{organisationId}/bank-statement-entries
- POST /api/deposit-batches/{batchId}/reconcile
- GET /api/organisations/{organisationId}/unallocated-cash-contributions

## Remaining limitations

This workflow does not yet upload or parse bank files in the UI, connect to bank APIs, allocate partial contribution receipts, or handle bank fees, shortages/overages, reversals, refunds or electronic settlement batches. Differences must be investigated; they are not silently adjusted.

Hosted PostgreSQL verification is required for the V16 migration, multi-credit reconciliation, over-banking prevention, concurrent reconciliation, scope enforcement and reconciliation locking. Do not use the system for live funds until those controls have been tested against the deployed database and operating procedures.


## Deposit evidence capture

Each deposit batch can retain up to 10 uploaded evidence files per request. Supported formats are JPEG, PNG, WebP and PDF, with a maximum size of 10 MB per file. Mobile browsers can open the rear camera from the capture control; existing photographs and scans can also be selected. Multiple images can represent a multi-page deposit package.

Original file bytes are stored in the private PostgreSQL database. The API derives the content type from the file signature rather than trusting the browser-provided MIME type, computes a SHA-256 digest, rejects an identical file already attached to the same batch and records the uploader and timestamp. Evidence is immutable through the application: there is no overwrite or delete operation. Access to metadata and content is checked against the deposit's geographical scope on every request. Content responses are marked no-store.

Evidence may be added while a batch is DRAFT, SUBMITTED or PARTIALLY_RECONCILED. Fully reconciled batches are read-only. The evidence file is supporting documentation only; upload does not verify bank settlement or authenticity. Scanner-generated PDFs are separate derivatives. The original upload remains preserved byte-for-byte.


## Deposit document scanner and PDF derivatives

The deposit evidence workspace includes a browser-based scanner for uploaded image evidence. It estimates document bounds from image/background contrast, then allows the operator to adjust each of the four page corners. The selected quadrilateral is rectified with a projective transform. Operators can retain original colour, apply grayscale, or apply a high-contrast grayscale preset. The corrected pages can be reordered and assembled into a multi-page A4 PDF (up to 10 pages).

The generated PDF is stored as a separate private derivative linked to the exact source evidence records. Original photographs remain byte-for-byte unchanged. PDF metadata and content are available only to users with finance-view permission in the deposit's geographical scope. Creation requires finance-deposit permission and is disabled after full reconciliation. The PDF is not an independent verification of the source document. Automatic boundary detection is a contrast-based estimate and must be checked before saving. OCR, text extraction, signature verification and tamper analysis are not included.


## Deposit-slip OCR assistance

The deposit evidence workspace can run English-language OCR against an uploaded image in the user's browser. It extracts candidate amount, bank reference and date values, then compares those candidates with the deposit batch's recorded amount, reference and date. Each value remains editable so the operator can correct recognition errors.

OCR is advisory only:
- The original evidence image is fetched through the authenticated evidence endpoint and processed in the browser; image bytes are not sent to an OCR service.
- The OCR engine and English language data are downloaded by Tesseract.js when first needed.
- A textual match is not proof of payment, bank settlement, document authenticity or tamper-free evidence.
- OCR does not modify a deposit, allocate receipts, submit a batch or reconcile a bank transaction.
- The operator must inspect the original slip and verify every extracted value independently.


### Human-confirmed OCR review history

Finance operators can record reviewed deposit-slip values after checking them against the original evidence photograph. The server independently compares the reviewed amount, bank reference and deposit date with the deposit batch, then stores an immutable review entry containing the original OCR candidates, the operator-reviewed values, comparison results, reviewer and timestamp.

Review history is read-only and visible to users with finance-view access. Recording a review requires finance-deposit permission and explicit confirmation that the original slip was inspected. Corrections are recorded as new entries; earlier entries are retained. This workflow does not change the deposit batch, allocate receipts or reconcile bank statement entries. A matching result remains a comparison against the batch's recorded values, not proof of bank settlement or document authenticity.
