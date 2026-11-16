-- Bank statement imports and controlled reconciliation exception workflow.

INSERT INTO admin_permissions (permission_code, name, description)
VALUES ('FINANCE_EXCEPTION', 'Manage reconciliation exceptions', 'Review and resolve bank reconciliation exceptions within assigned financial scope')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, 'FINANCE_EXCEPTION'
FROM admin_roles r
WHERE r.role_code = 'CENTRAL_ADMIN'
ON CONFLICT DO NOTHING;

CREATE TABLE bank_statement_imports (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    scope_unit_id UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    imported_rows INTEGER NOT NULL DEFAULT 0 CHECK (imported_rows >= 0),
    duplicate_rows INTEGER NOT NULL DEFAULT 0 CHECK (duplicate_rows >= 0),
    imported_by UUID NOT NULL REFERENCES admin_users(id),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_statement_import_scope_org FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units(id, organisation_id),
    CONSTRAINT uq_statement_import_file UNIQUE (organisation_id, scope_unit_id, file_sha256)
);

ALTER TABLE bank_statement_entries
    ADD COLUMN entry_type VARCHAR(10) NOT NULL DEFAULT 'CREDIT'
        CHECK (entry_type IN ('CREDIT', 'DEBIT')),
    ADD COLUMN import_id UUID REFERENCES bank_statement_imports(id);

CREATE INDEX ix_bank_statement_import_scope_date
    ON bank_statement_imports (organisation_id, scope_unit_id, imported_at DESC);

CREATE TABLE reconciliation_exceptions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    scope_unit_id UUID NOT NULL,
    source_type VARCHAR(20) NOT NULL CHECK (source_type IN ('DEPOSIT_BATCH', 'STATEMENT_ENTRY')),
    source_id UUID NOT NULL,
    exception_type VARCHAR(40) NOT NULL CHECK (exception_type IN (
        'DEPOSIT_NOT_FOUND', 'DEPOSIT_AMOUNT_MISMATCH', 'UNMATCHED_BANK_CREDIT', 'BANK_DEBIT_REVIEW'
    )),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED')),
    summary VARCHAR(500) NOT NULL,
    resolution_note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID REFERENCES admin_users(id),
    CONSTRAINT fk_reconciliation_exception_scope_org FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units(id, organisation_id),
    CONSTRAINT uq_reconciliation_exception_source UNIQUE (source_type, source_id, exception_type)
);

CREATE INDEX ix_reconciliation_exceptions_scope_status
    ON reconciliation_exceptions (organisation_id, scope_unit_id, status, created_at DESC);
