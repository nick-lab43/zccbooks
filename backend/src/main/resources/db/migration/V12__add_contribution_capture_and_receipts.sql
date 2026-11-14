-- Contribution capture and receipt lifecycle.
-- Posted contributions are immutable; corrections are handled by a separately authorised reversal workflow.

INSERT INTO admin_permissions (permission_code, name, description)
VALUES
  ('FINANCE_CAPTURE', 'Capture contributions', 'Record member contributions and issue receipts within assigned geographical scope')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT id, 'FINANCE_CAPTURE'
FROM admin_roles
WHERE role_code IN ('NYIKA_SECRETARY', 'NYIKA_ADMIN')
ON CONFLICT DO NOTHING;

CREATE TABLE financial_receipt_sequences (
    organisation_id UUID PRIMARY KEY REFERENCES organisations(id),
    next_number BIGINT NOT NULL DEFAULT 1 CHECK (next_number > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE contributions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    member_id UUID NOT NULL REFERENCES members(id),
    category_id UUID NOT NULL,
    stream_id UUID NOT NULL,
    scope_unit_id UUID NOT NULL,
    receipt_number VARCHAR(80) NOT NULL,
    receipt_sequence BIGINT NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'ZAR' CHECK (currency ~ '^[A-Z]{3}$'),
    contribution_date DATE NOT NULL,
    payment_method VARCHAR(24) NOT NULL CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'CARD', 'MOBILE_MONEY', 'OTHER')),
    payment_reference VARCHAR(120),
    note VARCHAR(500),
    idempotency_key VARCHAR(100) NOT NULL,
    captured_by UUID NOT NULL REFERENCES admin_users(id),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_contribution_receipt_org UNIQUE (organisation_id, receipt_number),
    CONSTRAINT uq_contribution_sequence_org UNIQUE (organisation_id, receipt_sequence),
    CONSTRAINT uq_contribution_idempotency_org UNIQUE (organisation_id, idempotency_key),
    CONSTRAINT fk_contribution_category_org FOREIGN KEY (category_id, organisation_id)
        REFERENCES contribution_categories(id, organisation_id),
    CONSTRAINT fk_contribution_stream_org FOREIGN KEY (stream_id, organisation_id)
        REFERENCES financial_streams(id, organisation_id),
    CONSTRAINT fk_contribution_scope_unit_org FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units(id, organisation_id)
);

CREATE INDEX ix_contributions_org_scope_date
    ON contributions (organisation_id, scope_unit_id, contribution_date DESC);
CREATE INDEX ix_contributions_member_date
    ON contributions (member_id, contribution_date DESC);
CREATE INDEX ix_contributions_category_scope_date
    ON contributions (category_id, scope_unit_id, contribution_date);

CREATE TABLE contribution_deposit_followups (
    id UUID PRIMARY KEY,
    contribution_id UUID NOT NULL UNIQUE REFERENCES contributions(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACKNOWLEDGED', 'RESOLVED')),
    assigned_to UUID REFERENCES admin_users(id),
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_contribution_deposit_followups_status
    ON contribution_deposit_followups (status, created_at);
