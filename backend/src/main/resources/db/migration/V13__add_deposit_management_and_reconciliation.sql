-- Deposit batches, receipt allocations and bank reconciliation controls.

INSERT INTO admin_permissions (permission_code, name, description)
VALUES
  ('FINANCE_DEPOSIT', 'Manage deposit batches', 'Create and submit deposit batches for cash contributions within assigned geographical scope'),
  ('FINANCE_RECONCILE', 'Reconcile bank deposits', 'Record bank statement entries and verify submitted deposits')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, 'FINANCE_DEPOSIT'
FROM admin_roles r
WHERE r.role_code IN ('NYIKA_SECRETARY', 'NYIKA_ADMIN', 'CENTRAL_ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, 'FINANCE_RECONCILE'
FROM admin_roles r
WHERE r.role_code = 'CENTRAL_ADMIN'
ON CONFLICT DO NOTHING;

CREATE TABLE deposit_batches (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    scope_unit_id UUID NOT NULL,
    deposit_date DATE NOT NULL,
    bank_name VARCHAR(120) NOT NULL,
    bank_reference VARCHAR(120) NOT NULL,
    expected_amount NUMERIC(14,2) NOT NULL CHECK (expected_amount > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'RECONCILED', 'EXCEPTION')),
    note VARCHAR(500),
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    CONSTRAINT fk_deposit_batch_scope_org FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units(id, organisation_id),
    CONSTRAINT uq_deposit_batch_reference UNIQUE (organisation_id, bank_reference)
);

CREATE TABLE deposit_allocations (
    id UUID PRIMARY KEY,
    deposit_batch_id UUID NOT NULL REFERENCES deposit_batches(id),
    contribution_id UUID NOT NULL UNIQUE REFERENCES contributions(id),
    allocated_amount NUMERIC(14,2) NOT NULL CHECK (allocated_amount > 0),
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_deposit_batches_org_scope_date
    ON deposit_batches (organisation_id, scope_unit_id, deposit_date DESC);
CREATE INDEX ix_deposit_allocations_batch
    ON deposit_allocations (deposit_batch_id);

CREATE TABLE bank_statement_entries (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    scope_unit_id UUID NOT NULL,
    transaction_date DATE NOT NULL,
    bank_reference VARCHAR(120) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    description VARCHAR(500),
    recorded_by UUID NOT NULL REFERENCES admin_users(id),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_statement_scope_org FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units(id, organisation_id),
    CONSTRAINT uq_statement_entry_identity UNIQUE (organisation_id, scope_unit_id, transaction_date, bank_reference, amount)
);

CREATE TABLE deposit_reconciliations (
    id UUID PRIMARY KEY,
    deposit_batch_id UUID NOT NULL UNIQUE REFERENCES deposit_batches(id),
    statement_entry_id UUID NOT NULL UNIQUE REFERENCES bank_statement_entries(id),
    matched_by UUID NOT NULL REFERENCES admin_users(id),
    matched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    match_note VARCHAR(500)
);
