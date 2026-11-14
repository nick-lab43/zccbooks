# Financial evidence, deposit workflow, administrator onboarding and reporting

**Status:** Proposed product requirements  
**Scope:** Requirements clarification only. This document does not implement the workflows or authorise database changes.

## 1. Product operating model

ZCC Books serves one provisioned organisation: Zion Christian Church. Central Administration governs the organisation, hierarchy, configuration, access and oversight. Administrators at delegated geographical scopes perform the operational work permitted to them.

The canonical geographical hierarchy is Country → Province/State → District → Nyika → Tabhera → Cell. A household is a membership grouping beneath a Cell, not a geographical level.

**Governance boundary:** Central Administration creates and maintains Countries, Provinces/States, Districts and Nyikas. A Nyika administrator maintains Tabheras and Cells beneath their own assigned Nyika. This delegated authority does not permit changing the Nyika's parent District, moving the Nyika, or accessing another Nyika. Central administrators retain organisation-wide oversight. All create, rename, deactivate and structural-change actions must be permission-checked and audited. Deactivation must be blocked or controlled when active descendants or financial/member records depend on the unit.

Every record and workflow must be scoped to the acting administrator's authorised unit and permitted descendants. A user must not choose or change their own organisational scope during onboarding.

## 2. Contribution-to-deposit lifecycle

A contribution and its receipt are recorded independently of when the money is banked.

1. An authorised local administrator records a contribution against the actual contributing member, contribution category, amount, date and applicable financial stream.
2. The system issues a receipt and preserves the posted transaction.
3. The contribution enters a pending-deposit follow-up queue. It is not treated as an immediate instruction to bank the money.
4. The queue remains visible on the appropriate operational dashboard until the contribution is allocated to a submitted deposit or otherwise resolved through an audited workflow.
5. The system displays the age and value of outstanding contributions. Escalation thresholds, if used, are centrally configurable.
6. Further contributions can be recorded while earlier contributions remain pending deposit.

The system must distinguish at least: not yet deposited, included in a draft deposit, submitted for review, accepted/reconciled, queried or rejected, and resolved through an authorised correction. Exact statuses should be finalised during detailed workflow design.

## 3. Deposit records and allocation breakdown

A bank deposit is a separate financial event and may cover multiple contribution transactions and categories. A single deposit may therefore have several allocation lines. **A deposit belongs to exactly one Nyika.** Contributions from different Nyikas must never be combined in one deposit record or allocated across Nyika boundaries. Each Nyika operates as a separate financial and operational unit for this workflow. A deposit may include contributions from multiple Cells or Tabheras within the same Nyika, subject to the authorised submitter's scope and the church's review rules.

A deposit record should capture:
- Geographical unit and submitting administrator.
- Bank and deposit date.
- Bank/deposit reference where available.
- Declared deposit amount and currency.
- Submission timestamp and status.
- Original uploaded photograph/file and processed scan.
- Allocation lines, notes and supporting explanation.
- Linked contribution transactions.
- Review outcome, reviewer, timestamps and query/rejection history.

Each allocation line should identify the contribution category or programme and amount, with optional additional classification where required. The total of allocation lines must be compared with the declared deposit amount. The system must also compare allocations with the selected contribution records.

Example: a R5,000 deposit may contain R2,000 Building Fund, R2,000 Tithing and R1,000 Youth. These allocations must be visible as structured lines, not only buried in free-text notes. A note field should remain available for explanations and for transcribing information written on the reverse of the physical slip.

The system must support partial allocation and partial banking as an exception-capable workflow, even if this is uncommon in normal practice. If only part of the collected money is banked, the administrator must record the amount deposited and leave the remainder visibly outstanding with an explanation or note. The system must prevent the same contribution amount from being allocated to multiple deposits. Unallocated, unbanked and unmatched amounts must remain visible as exceptions until resolved.

## 4. Deposit slip scanner

Provide an in-application document scanning workflow inspired by document-scanner applications such as CamScanner.

Expected capabilities:
- Capture using a mobile device camera or upload an existing image.
- Detect document edges and suggest a crop.
- Allow manual crop and corner adjustment.
- Perspective correction and rotation.
- Image enhancement presets, including original colour, enhanced colour, grayscale and black-and-white.
- Preview before saving.
- Save a readable processed image and PDF.
- Support multiple pages/images when a deposit has supporting pages.
- Attach the scan to the deposit submission and make it available to authorised central reviewers.

Preserve the original uploaded photograph/file as immutable evidence alongside the processed derivative. Record who captured/uploaded it and when. Reprocessing must create a new derivative/version rather than destroy the original. Scanner output is evidence presentation, not proof of bank settlement or authenticity.

Implementation should evaluate browser camera access and client-side document processing before selecting libraries. Do not assume a particular commercial SDK or impose a paid service without a separate decision. The experience must work on supported mobile browsers and provide a file-upload fallback.

## 5. Suggested allocation and matching

The application may suggest contribution matches using permitted signals such as:
- Organisational scope.
- Contribution category/programme.
- Amount and remaining unallocated amount.
- Contribution and deposit dates.
- Financial stream and other configured rules.

Suggestions must show why they were proposed and their confidence or match basis. An administrator must explicitly confirm matches. Ambiguous or conflicting matches must be left for manual resolution; the system must never silently allocate them.

Enforce backend invariants against over-allocation, duplicate allocation and cross-scope access. All allocation, unallocation, correction and review actions must be auditable. Posted contributions must not be silently rewritten.

## 6. Central financial review

Submitted deposit packages enter a Central Financial Administration review queue. Reviewers with the appropriate permission and scope can inspect:
- Original evidence and processed scan.
- Deposit metadata.
- Allocation breakdown and explanatory notes.
- Linked contribution and receipt records.
- Declared deposit total versus allocation total and linked contribution total.
- Outstanding/unallocated contributions and exceptions.
- Submission and review history.

Review outcomes should include acceptance, query/clarification and rejection with a mandatory reason where applicable. A submitter should be able to respond and resubmit without erasing the earlier submission or review history. A mismatch is an exception requiring investigation, not an automatic conclusion of misconduct.

## 7. Administrator invitation and Nyika workspace provisioning

Central Administration provisions an administrator account by selecting an existing geographical unit, role, name and email address.

The system:
1. Generates a username using the Nyika name or, preferably, a stable centrally assigned Nyika code, followed by `_admin` (for example, `seshego_admin`). The display name can remain the full official Nyika name. If a collision occurs, append a deterministic numeric suffix (for example, `seshego_admin2`) rather than assigning the same username to two people.
2. Creates a single-use, time-limited invitation.
3. Emails the invitation link to the supplied address.
4. Lets the recipient verify the invitation and establish their own password.
5. Activates the account with the preassigned username, application role and geographical scope.

Do not email a permanent plaintext password. Invitation tokens must be unpredictable, single-use, expire, and be stored securely (for example, as a hash). Resend, expiry, cancellation and already-used invitation cases need explicit handling. The user cannot choose a different Nyika, edit their assigned scope or grant themselves permissions during activation.

Username generation must be deterministic and collision-safe. Use a normalised, stable Nyika identifier rather than a mutable display name as the underlying source, so a later spelling correction does not unexpectedly change an administrator's login. Email delivery requires a configured provider; until configured, the system must not claim that an invitation was sent.

Application roles/permissions are separate from church appointments and positions.

## 8. Member positions and appointments

Member classification and church appointment are distinct concepts. A member may have a classification such as Youth and a position such as Minister or Headmaster.

Provide centrally governed position/office definitions and authorised assignment of those positions to members. Appointment records should include:
- Member.
- Position/office.
- Geographical scope.
- Effective start date and optional end date.
- Current/historical status.
- Appointing or recording administrator.
- Audit history and optional supporting note.

A member may have multiple concurrent appointments only where policy permits. Ended appointments remain visible in history. Church appointment must never automatically grant application access.

## 9. Church-branded Word reporting

Reporting is a core product capability. Authorised users should generate downloadable Microsoft Word (.docx) reports suitable for printing.

Initial report families:
- Census/member registers by geographical scope, household and configured classification.
- Contribution totals by category, programme/Murairo, financial stream, period and geographical unit.
- Contributor listings and ranked top-contributor reports, including configurable top-N such as top 10.
- Receipt registers and receipt status summaries.
- Deposit registers, deposit allocations, outstanding deposits and reconciliation exceptions.
- Income and expenditure reports.
- Membership changes, appointments and administrative/audit activity.

Report generation should support authorised scope, date range, filters, sorting and grouping. Standard, tested templates should be delivered first; a configurable report builder can follow. Reports must not bypass API permissions or expose personal/financial information outside the user's scope.

Each Word report should use the official ZCC star logo without stretching, recolouring or cropping it. Include the Zion Christian Church name, report title, relevant Country/Province/District/Nyika/Tabhera/Cell context, reporting period, generation timestamp and generated-by identity. Use consistent headers/footers, page numbers, readable tables, totals and clear currency formatting. Include appropriate confidentiality markings where required.

## 10. Security, audit and data integrity

- Enforce permissions and geographical scope in backend services, not only in the UI.
- Preserve original financial records and evidence.
- Use append-only or versioned histories for submissions, allocations and review decisions.
- Record actor, timestamp, action, target record and reason where applicable.
- Prevent duplicate and over-allocation of contributions.
- Keep personal data and financial evidence in private storage; do not expose public object URLs.
- Apply retention, access and export controls to scanned documents and generated reports.
- Treat OCR or machine extraction, if later introduced, as an assistive suggestion requiring human verification.

## 11. Delivery sequence and implementation status

1. **Organisation and access foundation — implemented:** canonical Country → Province/State → District → Nyika → Tabhera → Cell hierarchy, scoped administrator roles and append-only administrative audit events. Legacy branch records remain separate and must be reconciled before financial capture uses them.
2. **Membership and appointments — implemented:** Cell-based membership, household history, member contact profiles, centrally governed church positions and date-effective appointments.
3. **Financial Administration configuration — implemented in the current development branch:** centrally governed financial streams, contribution categories, geographical applicability and period-based targets. See `docs/financial-administration-foundation.md`. Contribution posting and target achievement calculations are not yet implemented.
4. **Contribution and receipt lifecycle — next:** post contributions against the actual member, approved category, amount, date, stream and authorised geographical scope; issue receipts; preserve immutable transaction history; and maintain pending-deposit follow-ups.
5. **Deposit package and allocation:** implement one-Nyika deposits, structured category/stream allocation lines, partial banking, contribution matching and Central Financial Administration review.
6. **Scanner experience:** implement capture/upload, crop, perspective correction, enhancement, original preservation and PDF/image output.
7. **Administrator invitations:** complete email delivery, invitation lifecycle, deterministic username generation and pre-scoped account activation.
8. **Reporting:** implement permission-scoped Word templates for census, contribution, receipt, deposit and expenditure reports; then expand report configuration.
9. **Verification:** test cross-Nyika isolation, partial deposits, multiple categories and streams per deposit, delayed banking, duplicate/over-allocation prevention, rejected/resubmitted packages, invitation expiry and report scope.

## 12. Decisions still requiring confirmation

- **Confirmed provisionally:** Main and Miscellaneous financial streams may appear on the same deposit slip/deposit record. The data model must retain each allocation line's stream so reporting and reconciliation can still separate them. This remains subject to revision if church policy later requires separate deposits.
- **Confirmed:** A deposit is restricted to one Nyika. It may include contributions from multiple Cells and Tabheras within that Nyika where the submitter is authorised to access them. Contributions from different Nyikas must never be combined.
- **Operational exception:** Partial banking is not expected to be common, but the system must support it. The administrator records the actual banked amount; the remainder stays outstanding and requires an explanation and follow-up.
- **Proposed username convention:** normalised Nyika identifier/name plus `_admin` (for example `seshego_admin`), with deterministic numeric suffixes for collisions. Prefer a stable Nyika code as the identifier source.
- The church-approved list of member positions and rules for concurrent appointments.
- Which report fields are sensitive and which roles may view named contributor rankings.
- The official source/format for the ZCC logo and any required report wording or confidentiality footer.
