-- Configurable account mapping foundation for future source-to-ledger posting.
-- This migration does not create journals or post any operational source records.
CREATE TABLE finance_contribution_account_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    category_id UUID NOT NULL,
    payment_method VARCHAR(24) NOT NULL
        CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'CARD', 'MOBILE_MONEY', 'OTHER')),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_finance_contribution_mapping UNIQUE (organisation_id, category_id, payment_method, currency),
    CONSTRAINT ck_finance_contribution_accounts_distinct CHECK (debit_account_id <> credit_account_id),
    CONSTRAINT fk_finance_contribution_category FOREIGN KEY (category_id, organisation_id)
        REFERENCES contribution_categories(id, organisation_id),
    CONSTRAINT fk_finance_contribution_debit FOREIGN KEY (debit_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency),
    CONSTRAINT fk_finance_contribution_credit FOREIGN KEY (credit_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency)
);

CREATE TABLE finance_expenditure_account_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    category_id UUID NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_finance_expenditure_mapping UNIQUE (organisation_id, category_id, currency),
    CONSTRAINT ck_finance_expenditure_accounts_distinct CHECK (debit_account_id <> credit_account_id),
    CONSTRAINT fk_finance_expenditure_category FOREIGN KEY (category_id, organisation_id)
        REFERENCES expenditure_categories(id, organisation_id),
    CONSTRAINT fk_finance_expenditure_debit FOREIGN KEY (debit_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency),
    CONSTRAINT fk_finance_expenditure_credit FOREIGN KEY (credit_account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency)
);

CREATE INDEX ix_finance_contribution_mapping_org
    ON finance_contribution_account_mappings (organisation_id, category_id, payment_method);
CREATE INDEX ix_finance_expenditure_mapping_org
    ON finance_expenditure_account_mappings (organisation_id, category_id);
