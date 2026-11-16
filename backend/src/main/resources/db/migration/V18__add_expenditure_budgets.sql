-- Versioned expenditure budgets and immutable amendment history.
INSERT INTO admin_permissions (permission_code, name, description) VALUES
('FINANCE_BUDGET_MANAGE', 'Manage expenditure budgets', 'Create and revise expenditure budgets across the organisation')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT id, 'FINANCE_BUDGET_MANAGE'
FROM admin_roles
WHERE role_code = 'CENTRAL_ADMIN'
ON CONFLICT DO NOTHING;

CREATE TABLE expenditure_budgets (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    unit_id UUID NOT NULL,
    category_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    budget_amount NUMERIC(14,2) NOT NULL CHECK (budget_amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'ZAR' CHECK (currency ~ '^[A-Z]{3}$'),
    note VARCHAR(1000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by UUID NOT NULL REFERENCES admin_users(id),
    updated_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_expenditure_budget_period CHECK (period_end >= period_start),
    CONSTRAINT fk_expenditure_budget_unit_org FOREIGN KEY (unit_id, organisation_id)
        REFERENCES organisational_units (id, organisation_id),
    CONSTRAINT fk_expenditure_budget_category_org FOREIGN KEY (category_id, organisation_id)
        REFERENCES expenditure_categories (id, organisation_id),
    CONSTRAINT uq_expenditure_budgets_id_org UNIQUE (id, organisation_id)
);

CREATE INDEX ix_expenditure_budgets_org_unit_period
    ON expenditure_budgets (organisation_id, unit_id, period_start, period_end);
CREATE INDEX ix_expenditure_budgets_category_currency
    ON expenditure_budgets (category_id, currency, period_start, period_end);

CREATE TABLE expenditure_budget_revisions (
    id UUID PRIMARY KEY,
    budget_id UUID NOT NULL REFERENCES expenditure_budgets(id),
    previous_amount NUMERIC(14,2) NOT NULL,
    revised_amount NUMERIC(14,2) NOT NULL CHECK (revised_amount > 0),
    previous_version INTEGER NOT NULL,
    new_version INTEGER NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    revised_by UUID NOT NULL REFERENCES admin_users(id),
    revised_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_expenditure_budget_revision_version UNIQUE (budget_id, new_version)
);

CREATE INDEX ix_expenditure_budget_revisions_budget
    ON expenditure_budget_revisions (budget_id, revised_at DESC);
