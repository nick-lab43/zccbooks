-- Immutable operational reversal events and linked reversing journal idempotency.
INSERT INTO admin_permissions (permission_code, name, description)
VALUES ('FINANCE_REVERSE', 'Reverse financial reconciliations',
        'Reverse an incorrect deposit bank reconciliation within assigned geographical scope')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT id, 'FINANCE_REVERSE' FROM admin_roles WHERE role_code = 'CENTRAL_ADMIN'
ON CONFLICT DO NOTHING;

CREATE TABLE deposit_reconciliation_reversals (
    id UUID PRIMARY KEY,
    reconciliation_id UUID NOT NULL UNIQUE REFERENCES deposit_reconciliations(id),
    reason VARCHAR(1000) NOT NULL,
    reversed_by UUID NOT NULL REFERENCES admin_users(id),
    reversed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_deposit_reconciliation_reversals_reversed_at
    ON deposit_reconciliation_reversals (reversed_at DESC);

CREATE UNIQUE INDEX uq_finance_journal_deposit_settlement_reversal_source
    ON finance_journals (organisation_id, source_id)
    WHERE source_type = 'DEPOSIT_SETTLEMENT_REVERSAL' AND source_id IS NOT NULL;
