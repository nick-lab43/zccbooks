-- Configurable bank-to-cash asset transfers for verified deposit reconciliations.
CREATE TABLE finance_deposit_settlement_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    bank_name VARCHAR(120) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'ZAR' CHECK (currency = 'ZAR'),
    bank_account_id UUID NOT NULL,
    cash_account_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_finance_deposit_settlement_mapping UNIQUE (organisation_id, LOWER(bank_name), currency),
    CONSTRAINT ck_finance_deposit_settlement_accounts_distinct CHECK (bank_account_id <> cash_account_id),
    CONSTRAINT fk_finance_deposit_bank_account FOREIGN KEY (bank_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency),
    CONSTRAINT fk_finance_deposit_cash_account FOREIGN KEY (cash_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency)
);
CREATE INDEX ix_finance_deposit_settlement_mapping_org
    ON finance_deposit_settlement_mappings (organisation_id);
CREATE UNIQUE INDEX uq_finance_journal_deposit_settlement_source
    ON finance_journals (organisation_id, source_id)
    WHERE source_type = 'DEPOSIT_SETTLEMENT' AND source_id IS NOT NULL;
