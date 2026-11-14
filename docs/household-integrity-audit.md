# Household integrity and member lifecycle audit

**Audit baseline:** `6c92fbfba8cd94f7d84111d0bfb8f94be8cbecee` (main, after PR #19)

## Scope

This audit reviews the Cell-based member and household implementation in the backend controllers, Flyway migrations, authorisation service, audit service and frontend household workspace. It does not change application behaviour or database state.

## Verified implementation

- Members registered through the Cell workflow are assigned an organisation and Cell; the legacy branch location remains supported for older records.
- Households belong to a Cell and are separate from the geographical hierarchy.
- Household membership rows preserve effective-date history.
- A partial unique index prevents a member from having more than one active household membership.
- Database triggers require household members to be in the same Cell and require a household head to have an active membership in that household.
- The API exposes active household head candidates and an endpoint to designate or clear a head.
- Unit-scoped permission checks are applied to the reviewed Cell and household endpoints.
- The frontend loads household members and active-household assignments when a Cell is selected.

## Findings

### H1 — Member deactivation can leave an invalid household head

The V6 trigger validates the household when its head reference is inserted or changed. It does not run when a member's `active` flag changes. A member can therefore become inactive while still referenced as the household head.

**Required correction:** enforce this invariant at database level when member status changes, and expose a controlled deactivation workflow that first clears or replaces the head designation.

### H2 — Cell transfer is not implemented

There is no API workflow to transfer a member from one Cell to another. Directly changing `members.cell_id` would also leave historical/current household membership semantics unresolved.

**Required correction:** implement an atomic transfer operation that:
1. Verifies the member and both Cells belong to the same organisation.
2. Checks management permission for both source and destination Cells.
3. Ends the member's active household membership, if one exists.
4. Clears the household head reference first if the member is the current head.
5. Changes the member's Cell.
6. Writes an audit event containing source Cell, destination Cell and any household membership closed.
7. Preserves the historical household membership row.

The system must not silently move the entire household when one member transfers.

### H3 — Household membership mutations are not audited

The reviewed add-member and end-membership endpoints mutate records without calling the administrative audit service.

**Required correction:** record household member added/removed events with household ID, member ID, relationship label and effective date. Do not store unnecessary personal data in audit details.

### H4 — Relationship labels are free text

The frontend asks administrators to type a relationship label. There is no organisation-scoped catalogue, no active/inactive state and no controlled selection.

**Required correction:** introduce an organisation-configurable relationship catalogue with stable codes and display labels. Historical membership rows must retain the label/code that applied when the relationship was recorded; changing a catalogue label must not rewrite history.

### H5 — Household head validation needs broader coverage

The current head-candidate query correctly returns active members with an active membership in the selected household. The V6 database trigger checks the same rule on head assignment. The missing member-deactivation guard is documented in H1.

**Required correction:** test valid assignment, clearing, replacement, cross-household member rejection, inactive-member rejection and attempted deactivation of a current head.

### H6 — Household workflow test coverage is absent

The current backend test tree contains an authorisation service test, but no integration tests for household/member database constraints or controller workflows.

**Required correction:** add PostgreSQL-backed integration tests covering:
- same-Cell membership enforcement;
- one active household per member;
- head designation and replacement;
- member deactivation while designated as head;
- ending a head's active household membership;
- transfer with and without an active household;
- source/destination authorisation;
- audit events and retained history.

## Recommended implementation order

1. Add database-level lifecycle protection and tests for household-head integrity.
2. Add audited household membership mutations.
3. Implement atomic Cell transfer with history preservation and scoped authorisation.
4. Add the configurable relationship catalogue and preserve historical labels.
5. Add complete PostgreSQL integration coverage for the above.

## Safety constraints

- V1–V6 are deployed and immutable. Use a new Flyway migration for schema changes.
- Do not manually execute SQL or use Flyway repair.
- Do not silently delete or rewrite household membership history.
- Do not automatically transfer a household when a member changes Cell.
- Do not grant application permissions from church appointments.
- All operations must be scoped to the organisation and relevant Cell(s).
- Financial workflows remain out of scope until member and household integrity is reliable.
