-- Central financial configuration and geographical target foundation.
-- Contribution posting and deposit workflows are deliberately introduced in later migrations.

INSERT INTO admin_permissions (permission_code, name, description) VALUES
('FINANCE_VIEW', 'View financial administration', 'View centrally configured financial categories and targets within assigned scope'),
('FINANCE_CONFIGURE', 'Configure financial administration', 'Configure financial streams, contribution categories and financial targets');

INSERT INTO admin_role_permissions (role_id, permission_code) VALUES
('10000000-0000-0000-0000-000000000006', 'FINANCE_VIEW'),
('10000000-0000-0000-0000-000000000007', 'FINANCE_VIEW');

CREATE TABLE financial_streams (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_financial_streams_id_org UNIQUE (id, organisation_id),
    CONSTRAINT uq_financial_streams_code UNIQUE (organisation_id, code)
);

CREATE TABLE contribution_categories (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    stream_id UUID NOT NULL,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    contribution_type VARCHAR(20) NOT NULL
        CHECK (contribution_type IN ('MANDATORY', 'VOLUNTARY', 'PROJECT')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_contribution_categories_id_org UNIQUE (id, organisation_id),
    CONSTRAINT uq_contribution_categories_code UNIQUE (organisation_id, code),
    CONSTRAINT fk_contribution_category_stream
        FOREIGN KEY (stream_id, organisation_id)
        REFERENCES financial_streams (id, organisation_id)
);

CREATE TABLE contribution_category_scope_levels (
    category_id UUID NOT NULL REFERENCES contribution_categories(id),
    scope_level VARCHAR(16) NOT NULL CHECK (scope_level IN ('NYIKA', 'TABHERA', 'CELL')),
    PRIMARY KEY (category_id, scope_level)
);

CREATE TABLE financial_targets (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    category_id UUID NOT NULL,
    unit_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    target_amount NUMERIC(14,2) NOT NULL CHECK (target_amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'ZAR' CHECK (currency ~ '^[A-Z]{3}$'),
    note VARCHAR(1000),
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_financial_target_period CHECK (period_end >= period_start),
    CONSTRAINT fk_financial_target_category
        FOREIGN KEY (category_id, organisation_id)
        REFERENCES contribution_categories (id, organisation_id),
    CONSTRAINT fk_financial_target_unit
        FOREIGN KEY (unit_id, organisation_id)
        REFERENCES organisational_units (id, organisation_id)
);

CREATE INDEX ix_financial_streams_org_active
    ON financial_streams (organisation_id, active, name);
CREATE INDEX ix_contribution_categories_org_stream
    ON contribution_categories (organisation_id, stream_id, active);
CREATE INDEX ix_financial_targets_org_unit_period
    ON financial_targets (organisation_id, unit_id, period_start, period_end);
CREATE INDEX ix_financial_targets_category_period
    ON financial_targets (category_id, period_start, period_end);
