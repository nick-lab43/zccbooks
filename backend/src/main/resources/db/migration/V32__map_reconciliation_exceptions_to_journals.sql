-- Organisation-configurable account mappings for classified reconciliation adjustments.
CREATE TABLE finance_exception_account_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    resolution_code VARCHAR(40) NOT NULL
        CHECK (resolution_code IN ('BANK_FEE', 'SHORT_DEPOSIT', 'OVER_DEPOSIT', 'REFUND_OR_REVERSAL')),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_finance_exception_mapping_distinct_accounts
        CHECK (debit_account_id <> credit_account_id),
    CONSTRAINT uq_finance_exception_mapping_org_code_currency
        UNIQUE (organisation_id, resolution_code, currency),
    CONSTRAINT fk_finance_exception_mapping_debit_account
        FOREIGN KEY (debit_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency),
    CONSTRAINT fk_finance_exception_mapping_credit_account
        FOREIGN KEY (credit_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency)
);

CREATE INDEX ix_finance_exception_mapping_org
    ON finance_exception_account_mappings (organisation_id, resolution_code, currency);

-- A reconciliation exception can generate at most one journal, including drafts.
CREATE UNIQUE INDEX uq_finance_journal_reconciliation_source
    ON finance_journals (source_type, source_id)
    WHERE source_type = 'RECONCILIATION_EXCEPTION' AND source_id IS NOT NULL;
