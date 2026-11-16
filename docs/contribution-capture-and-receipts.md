# Contribution Capture and Receipt Lifecycle

## Scope

This phase adds the first operational financial-record workflow on top of the V11 Financial Administration configuration foundation. It supports member contribution capture, immutable posted records, receipt retrieval, target progress and a pending-deposit follow-up queue.

## Governance and permissions

- Central Administration configures financial streams, categories, category applicability and targets.
- FINANCE_CAPTURE authorises contribution capture and follow-up updates within assigned geographical scope.
- FINANCE_VIEW authorises read access within assigned scope.
- The V12 migration grants both permissions to seeded NYIKA_SECRETARY, NYIKA_ADMIN and CENTRAL_ADMIN roles when those roles exist. Custom roles can be granted these permissions through access administration.
- The capture endpoint checks member active status, category and stream status, selected unit status, category scope level, and that the selected financial unit is an ancestor of the member's Cell.
- Member lookup for capture is a finance-specific endpoint protected by FINANCE_CAPTURE; it does not require broad membership-register access.

## Posting and receipt integrity

Each posted contribution stores its organisation, member, category, stream, geographical scope, amount, currency, contribution date, payment method, optional payment reference, note, idempotency key, capturing administrator and timestamp.

Receipt numbers are allocated from a per-organisation sequence while holding a row lock. This serialises concurrent allocations and avoids duplicate receipt numbers. The organisation/idempotency-key constraint prevents a retried request from creating a second contribution. Receipt numbers are unique within an organisation.

Posted contribution rows are not edited by this phase. A correction, cancellation or refund must be implemented as a separately authorised, audited reversal workflow; operators must not overwrite the original record.

## Endpoints

- POST /api/organisations/{organisationId}/contributions — post a contribution and issue a receipt.
- GET /api/organisations/{organisationId}/financial-units — list geographical units visible for contribution capture.
- GET /api/units/{cellId}/financial-members?scopeUnitId={unitId} — list active members of a Cell within the caller's authorised financial scope.
- GET /api/contributions/{contributionId}/receipt — retrieve a receipt within the caller's view scope.
- GET /api/organisations/{organisationId}/contributions?scopeUnitId={unitId} — list contributions for a scope, optionally filtered by from and to.
- GET /api/organisations/{organisationId}/financial-target-progress — calculate received totals and achievement percentages for targets visible to the caller.
- GET /api/organisations/{organisationId}/deposit-followups?scopeUnitId={unitId} — list unresolved deposit follow-ups in a scope.
- PATCH /api/deposit-followups/{followupId} — acknowledge or resolve a follow-up within the caller's capture scope.

## Target calculations

A contribution counts toward a target only when organisation, category, exact geographical unit and contribution date all match. This avoids counting a lower-level receipt in a higher-level target and then counting it again in a separate lower-level target. A target's achievement can exceed 100%; the value is not capped.

## Pending deposits

Only CASH contributions create deposit follow-ups. Their status follows the linked deposit workflow:

- PENDING: cash receipt has not been allocated.
- ACKNOWLEDGED: an authorised finance capturer has acknowledged the outstanding follow-up.
- IN_DRAFT: the receipt is allocated to a draft deposit batch.
- SUBMITTED: the containing deposit batch has been submitted for reconciliation.
- PARTIALLY_BANKED: the batch has received a partial bank credit and retains an outstanding balance.
- RESOLVED: the batch has been fully reconciled against bank credits, or an authorised capturer has manually resolved an unallocated receipt with a mandatory reason.

Removing a receipt from a draft batch returns its follow-up to PENDING. A follow-up linked to an active deposit batch cannot be manually resolved. Resolved follow-ups cannot be reopened. Non-cash payment methods do not enter the cash deposit follow-up queue.

## Verification still required

The hosted preview and PostgreSQL deployment must verify Flyway V12, concurrent receipt allocation, idempotent retries, scope-bound member lookup, unauthorised cross-scope access, receipt rendering, target totals, and follow-up transitions. Do not use the development build for live financial records until these checks and the remaining operational controls are completed.
