ALTER TABLE reconciliation_exceptions
    ADD COLUMN resolution_code VARCHAR(40)
        CHECK (resolution_code IS NULL OR resolution_code IN (
            'BANK_FEE', 'SHORT_DEPOSIT', 'OVER_DEPOSIT', 'WRONG_REFERENCE',
            'DUPLICATE_STATEMENT_ENTRY', 'NON_DEPOSIT_TRANSACTION',
            'REFUND_OR_REVERSAL', 'OTHER'
        )),
    ADD COLUMN resolution_amount NUMERIC(14, 2)
        CHECK (resolution_amount IS NULL OR resolution_amount > 0);

ALTER TABLE reconciliation_exception_history
    ADD COLUMN resolution_code VARCHAR(40)
        CHECK (resolution_code IS NULL OR resolution_code IN (
            'BANK_FEE', 'SHORT_DEPOSIT', 'OVER_DEPOSIT', 'WRONG_REFERENCE',
            'DUPLICATE_STATEMENT_ENTRY', 'NON_DEPOSIT_TRANSACTION',
            'REFUND_OR_REVERSAL', 'OTHER'
        )),
    ADD COLUMN resolution_amount NUMERIC(14, 2)
        CHECK (resolution_amount IS NULL OR resolution_amount > 0);

CREATE INDEX ix_reconciliation_exceptions_resolution
    ON reconciliation_exceptions (organisation_id, scope_unit_id, resolution_code)
    WHERE status = 'RESOLVED';
