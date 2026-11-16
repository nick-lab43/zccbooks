-- Expenditure capture, independent approval and payment settlement.
INSERT INTO admin_permissions (permission_code, name, description) VALUES
('FINANCE_EXPENSE_CAPTURE', 'Capture expenditure', 'Create and submit expenditure records within assigned geographical scope'),
('FINANCE_EXPENSE_APPROVE', 'Approve expenditure', 'Approve, reject and record payment of expenditure within assigned geographical scope')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT id, 'FINANCE_EXPENSE_CAPTURE'
FROM admin_roles
WHERE role_code IN ('CENTRAL_ADMIN', 'NYIKA_ADMIN', 'FINANCIAL_OFFICER')
ON CONFLICT DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT id, 'FINANCE_EXPENSE_APPROVE'
FROM admin_roles
WHERE role_code = 'CENTRAL_ADMIN'
ON CONFLICT DO NOTHING;

CREATE TABLE expenditure_categories (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_expenditure_categories_id_org UNIQUE (id, organisation_id)
);

CREATE UNIQUE INDEX uq_expenditure_categories_code_ci
    ON expenditure_categories (organisation_id, LOWER(code));

CREATE TABLE expenditures (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    scope_unit_id UUID NOT NULL,
    category_id UUID NOT NULL,
    expense_date DATE NOT NULL,
    payee VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    reference VARCHAR(120),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'ZAR' CHECK (currency ~ '^[A-Z]{3}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PAID')),
    captured_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    reviewed_by UUID REFERENCES admin_users(id),
    reviewed_at TIMESTAMPTZ,
    review_note VARCHAR(500),
    paid_by UUID REFERENCES admin_users(id),
    paid_at TIMESTAMPTZ,
    payment_reference VARCHAR(120),
    CONSTRAINT fk_expenditure_scope_org FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units (id, organisation_id),
    CONSTRAINT fk_expenditure_category_org FOREIGN KEY (category_id, organisation_id)
        REFERENCES expenditure_categories (id, organisation_id)
);

CREATE INDEX ix_expenditures_org_scope_date
    ON expenditures (organisation_id, scope_unit_id, expense_date DESC);
CREATE INDEX ix_expenditures_status_scope
    ON expenditures (scope_unit_id, status, expense_date DESC);
