# Architecture

## Purpose

ZCC Books is a configurable, multi-branch church finance and audit management platform. It digitises branch financial records while preserving traceability and the ability to reconcile records against receipt and bank-deposit evidence.

## Application boundaries

- `frontend/`: React and TypeScript user interface.
- `backend/`: Java and Spring Boot API and business rules.
- `database/`: versioned PostgreSQL schema migrations.
- `docs/`: architecture and domain decisions.

## Core concepts

- Organisation, region/district and branch establish the oversight hierarchy.
- Members belong to a branch and may be assigned configurable member classifications.
- Contribution categories describe the purpose/type of a contribution; they are not member classifications.
- A financial transaction records money received. A receipt is evidence issued for a transaction. A bank deposit is a separate event and may aggregate multiple transactions.
- Main and miscellaneous financial streams remain separately identifiable for reporting and reconciliation.
- Expenditure is recorded as its own event and must not be treated as a contribution or receipt.
- Audit events preserve who did what, when, and why.

## Security and financial integrity

Authorisation is enforced by the backend, with branch scope applied to branch-level users. Posted financial records are not silently overwritten or deleted. Corrections are represented by traceable reversals or adjustments. Receipt identifiers are controlled and unique. Reconciliation differences are exceptions for investigation, not automatic findings of misconduct.

## External integrations

Membership lookup and messaging are behind replaceable integration boundaries. The initial demonstration uses fictional seed data and a mock membership provider. No access to a production ZCC membership API or messaging provider is assumed.

## Initial delivery sequence

1. Project foundation and database baseline.
2. Branches, members and configurable classifications.
3. Contributions and ZCC-style receipts.
4. Deposits and expenditure.
5. Reconciliation and audit reporting.
6. Central oversight and branch reporting.
