-- Contribution corrections are represented by immutable reversal records.
INSERT INTO admin_permissions (permission_code, name, description)
VALUES ('FINANCE_REVERSE', 'Reverse contributions', 'Reverse posted contributions before they enter a deposit batch')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT id, 'FINANCE_REVERSE' FROM admin_roles WHERE role_code = 'CENTRAL_ADMIN'
ON CONFLICT DO NOTHING;

CREATE TABLE contribution_reversals (
    id UUID PRIMARY KEY,
    contribution_id UUID NOT NULL UNIQUE REFERENCES contributions(id),
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) > 0),
    reversed_by UUID NOT NULL REFERENCES admin_users(id),
    reversed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_contribution_reversals_reversed_at
    ON contribution_reversals (reversed_at DESC);

CREATE FUNCTION prevent_contribution_reversal_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Contribution reversal records are immutable'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_contribution_reversals_immutable
    BEFORE UPDATE OR DELETE ON contribution_reversals
    FOR EACH ROW EXECUTE FUNCTION prevent_contribution_reversal_mutation();
