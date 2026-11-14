# ZCC Books — implementation gap analysis

**Audit baseline:** `bdad7bcf7c7443bdc0115ebda2d6abd5c20f05f3` (main, after PR #12)

## Current foundation

The repository has a working React/TypeScript frontend, Spring Boot API and PostgreSQL/Flyway schema. The current workspace exposes Overview, Organisation, Membership and Access Control.

Implemented foundations include:

- Administrator sign-in using HTTP Basic over HTTPS, BCrypt password hashes, role/permission records and scoped assignments.
- Administrative audit events for administrator and access-control actions.
- A six-level geographical hierarchy: Country, Province/State, District, Nyika, Tabhera and Cell.
- Households stored separately under Cells, with date-effective household membership history and a database constraint allowing one active household per member.
- APIs to create/list households, list household members, add a member to a household and end a household membership.
- Organisation, legacy branch, member classification and branch-based member register APIs.
- Forward-only Flyway migrations through V5. Applied migrations V1–V4 must remain immutable.

## Gaps that block the intended operating model

### 1. Geographical configuration and legacy branches

- The six hierarchy levels are hard-coded in both the backend and frontend. The required central governance model needs configurable hierarchy templates or permitted structures, including country-specific variation where authorised.
- The older `branches` entity remains separate from the geographical unit tree. Members still require a non-null `branch_id`, while household placement is linked to a Cell. The relationship between a legacy branch and the canonical geographical structure must be explicitly modelled before branch-based membership can be retired.
- The organisation-wide hierarchy endpoint requires organisation-level permission. Unit-scoped administrators currently cannot use that endpoint to load their permitted structure.

### 2. Local administrator scope

- Unit-level permission checks exist for child-unit and household operations.
- However, organisation listing, organisation hierarchy loading and the legacy member register still rely on GLOBAL or ORGANISATION scope. A Nyika- or Cell-scoped administrator cannot complete the intended local onboarding journey through the current UI.
- Role assignment is restricted to a global administrator. Delegated local authority has not been implemented.
- Application access roles must remain separate from church appointments. A member appointed as a treasurer, minister or secretary must not receive software permissions automatically.

### 3. Membership and households

- The current Membership screen creates members against a legacy branch, not directly within a local Cell/Nyika workflow.
- Household create/list is present in the hierarchy screen. Household member add/end APIs exist, but the screen does not expose those workflows.
- The database has a household-head reference, but there is no complete workflow to designate and validate a household head.
- The current UI does not provide a complete Nyika secretary onboarding process: register a member, place that member in the appropriate Cell/household, preserve the ZCC app number and retain membership history.

### 4. Church appointments and organisational offices

No appointment/position catalogue or member appointment workflow is currently present. The application needs centrally governed definitions for offices, ministries and committees, plus authorised local assignment of registered members. Appointment history, effective dates, scope and audit events must be retained. These appointments are distinct from application roles and permissions.

### 5. Financial administration and operations

The V5 baseline contains `FINANCE_CAPTURE` and `FINANCE_RECONCILE` permission codes and a Financial Officer system role, but the financial domain is not implemented.

Missing capabilities include:

- Central Financial Administration ownership of contribution categories and their rules.
- Category properties such as mandatory/obligatory, voluntary, project-specific, eligibility and applicable periods.
- Financial targets assigned to organisational units, with direct targets distinguished from targets distributed by a parent.
- Contribution capture against the actual contributing member, category, amount, date and payment reference.
- Immutable posted transactions and traceable corrections.
- Receipt generation and receipt registers.
- Separate main and miscellaneous streams.
- Bank deposits, allocations and reconciliation.
- Miscellaneous income and expenditure.
- Financial exceptions and independent audit review.

A target is an organisational objective; it must not automatically become an individual member debt. Parent and child reporting must avoid double-counting distributed targets.

### 6. Audit and documentation

- The existing audit log covers administrative actions. Financial transaction history, reconciliation exceptions and independent audit workflows do not exist yet.
- README sections describing authentication as not implemented are stale and contradict the later administrator-access instructions in the same file.
- The canonical spelling/label for the fifth geographical level should be confirmed before it is changed in persisted data or user-facing records.

## Recommended implementation sequence

1. **Access and membership foundation:** fix organisation discovery and hierarchy loading for unit-scoped administrators; establish a canonical relationship between members, Cells and legacy branches; complete household head and member assignment workflows; add tests proving that a Nyika administrator cannot read or modify another Nyika.
2. **Church appointments:** add configurable position/office and committee definitions, scoped member appointments, appointment history and audit events. Keep these separate from application roles.
3. **Central Financial Administration:** add configurable contribution categories and policies, mandatory/voluntary classification, applicability and target allocation with direct/distributed provenance.
4. **Financial capture:** add member-linked contributions, immutable posting, receipt numbering, main/miscellaneous streams, deposits and expenditure.
5. **Reconciliation and audit:** reconcile register entries, receipts and deposits; support aggregate deposit allocations; report exceptions without treating missing evidence as an automatic finding of misconduct.
6. **Governance and reporting:** complete configurable hierarchy templates, delegation controls, consolidated reports and historical views. Retire or map legacy branch concepts only after data and workflow implications are tested.

## Branding change in this delivery

The uploaded official ZCC star artwork replaces the letter “Z” mark in the existing top-left masthead position. The same masthead is used by the sign-in and authenticated workspace views; no additional logo placements are introduced.
