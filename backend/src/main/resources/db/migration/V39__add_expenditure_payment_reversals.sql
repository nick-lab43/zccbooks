-- Immutable payment reversal events and source-linked reversing journal idempotency.
CREATE TABLE expenditure_payment_reversals (
    id UUID PRIMARY KEY,
    expenditure_id UUID NOT NULL UNIQUE REFERENCES expenditures(id),
    reason VARCHAR(1000) NOT NULL CHECK (LENGTH(BTRIM(reason)) > 0),
    reversed_by UUID NOT NULL REFERENCES admin_users(id),
    reversed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_expenditure_payment_reversals_reversed_at
    ON expenditure_payment_reversals (reversed_at DESC);

CREATE UNIQUE INDEX uq_finance_journal_expenditure_payment_reversal_source
    ON finance_journals (organisation_id, source_id)
    WHERE source_type = 'EXPENDITURE_PAYMENT_REVERSAL' AND source_id IS NOT NULL;
