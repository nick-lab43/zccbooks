# Financial Administration foundation

**Delivery:** Financial Administration foundation — migration V11.

## Purpose and boundary

Financial Administration is a separate department from General Administration, Membership and System Administration. It provides centrally governed configuration for the financial workflows that will be implemented in subsequent phases.

Central Administration controls financial streams, contribution categories, category applicability and financial targets. Financial Officers and Auditors receive scoped read access to configuration and targets through the new `FINANCE_VIEW` permission. Existing `FINANCE_CAPTURE` and `FINANCE_RECONCILE` permissions remain separate and are not used to grant configuration authority.

The current implementation does not post contributions, issue receipts, calculate target achievement or create deposits.

## Financial streams

Streams classify financial activity for later reporting and reconciliation. They are organisation-specific, centrally managed and independently active/deactivated.

The model does not hard-code stream names. Central Administration can configure the church-approved streams, including Main and Miscellaneous where required. A stream cannot be deactivated while it has active contribution categories.

## Contribution categories

A category belongs to one financial stream and has a unique organisation-wide code. Its contribution type is one of:

- `MANDATORY` — centrally specified obligations.
- `VOLUNTARY` — voluntary contributions.
- `PROJECT` — project or special-purpose funds.

Categories can apply at one or more geographical levels: Nyika, Tabhera and Cell. This supports categories such as Tithing that need to be administered at Nyika and Tabhera levels without duplicating the category definition.

Categories are centrally created, updated and deactivated. A category's applicability cannot be changed to exclude a geographical level that already has financial targets.

## Financial targets

A target records:

- Contribution category.
- One geographical unit (Nyika, Tabhera or Cell).
- Effective period start and end dates.
- Positive target amount and ISO currency code.
- Optional explanatory note.
- Recording administrator and creation timestamp.

Targets are centrally created and cannot overlap for the same category and geographical unit. A category must be active and configured for the selected unit's geographical level. The unit must be active and belong to the selected organisation.

The target register is visible to administrators with `FINANCE_VIEW`, filtered to the geographical units covered by their active role assignments. Central Administration has organisation-wide visibility.

Target achievement percentage is intentionally not calculated yet. It must be derived from posted contribution transactions, not guessed or displayed as zero before contribution capture exists.

## API

- `GET /api/organisations/{organisationId}/financial-management-access` — determine whether the signed-in administrator has Central Administration configuration authority.
- `GET /api/organisations/{organisationId}/financial-streams` — list configured streams.
- `POST /api/organisations/{organisationId}/financial-streams` — create a stream; Central Administration only.
- `PATCH /api/financial-streams/{streamId}` — rename or change stream status; Central Administration only.
- `GET /api/organisations/{organisationId}/contribution-categories` — list configured categories and their scope levels.
- `POST /api/organisations/{organisationId}/contribution-categories` — create a category; Central Administration only.
- `PATCH /api/contribution-categories/{categoryId}` — update or deactivate a category; Central Administration only.
- `GET /api/organisations/{organisationId}/financial-targets` — list targets visible within the administrator's financial scope.
- `POST /api/organisations/{organisationId}/financial-targets` — create a target; Central Administration only.

## Database and audit

Flyway migration V11 adds:

- `financial_streams`
- `contribution_categories`
- `contribution_category_scope_levels`
- `financial_targets`

The migration adds `FINANCE_VIEW` and `FINANCE_CONFIGURE` permissions. Financial Officers and Auditors receive scoped view permission. Central Administrators receive both new permissions. Configuration write endpoints additionally enforce the Central Administrator identity in the backend; a frontend permission alone is never sufficient.

Create, update and deactivation actions are recorded in the existing append-only administrator audit log. Targets are not silently overwritten or deleted. No contribution, receipt, deposit or bank data is created by this phase.

## Next implementation phase

Implement the contribution posting and receipt lifecycle:

1. Record the actual contributing member, category, amount, date, financial stream and authorised geographical scope.
2. Validate the category's configured scope level and active status.
3. Issue a unique, traceable receipt.
4. Preserve posted transactions; corrections must be separately authorised and audited.
5. Place contributions in the pending-deposit follow-up queue.
6. Calculate target progress from eligible posted contributions without double-counting or crossing Nyika boundaries.

Deposit records, allocation lines, scanning and Central Financial Administration review follow after contribution posting and receipt issuance are stable.
