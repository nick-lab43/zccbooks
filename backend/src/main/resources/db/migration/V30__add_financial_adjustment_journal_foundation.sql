-- Double-entry adjustment journal foundation.
-- This migration creates the controlled ledger structures; posting APIs and UI follow separately.

INSERT INTO admin_permissions (permission_code, name, description) VALUES
('FINANCE_JOURNAL_VIEW', 'View financial journals', 'View journal entries within assigned financial scope'),
('FINANCE_JOURNAL_CREATE', 'Create financial journals', 'Create and submit draft adjustment journals within assigned financial scope'),
('FINANCE_JOURNAL_APPROVE', 'Approve financial journals', 'Approve and post adjustment journals within assigned financial scope')
ON CONFLICT (permission_code) DO NOTHING;

CREATE TABLE finance_accounts (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    account_code VARCHAR(40) NOT NULL,
    account_name VARCHAR(120) NOT NULL,
    account_type VARCHAR(20) NOT NULL
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    currency CHAR(3) NOT NULL DEFAULT 'ZAR' CHECK (currency ~ '^[A-Z]{3}$'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_finance_accounts_id_org UNIQUE (id, organisation_id, currency)
);

CREATE UNIQUE INDEX uq_finance_accounts_code_ci
    ON finance_accounts (organisation_id, LOWER(account_code));

CREATE TABLE finance_journals (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    scope_unit_id UUID NOT NULL,
    journal_number VARCHAR(80) NOT NULL,
    journal_date DATE NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'ZAR' CHECK (currency ~ '^[A-Z]{3}$'),
    description VARCHAR(1000) NOT NULL CHECK (length(trim(description)) > 0),
    source_type VARCHAR(40),
    source_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED')),
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    approved_by UUID REFERENCES admin_users(id),
    approved_at TIMESTAMPTZ,
    posted_by UUID REFERENCES admin_users(id),
    posted_at TIMESTAMPTZ,
    CONSTRAINT uq_finance_journals_number UNIQUE (organisation_id, journal_number),
    CONSTRAINT fk_finance_journal_scope_org
        FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units(id, organisation_id),
    CONSTRAINT ck_finance_journal_approval_actor
        CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT ck_finance_journal_posting_metadata
        CHECK ((status <> 'POSTED') OR
            (approved_by IS NOT NULL AND approved_at IS NOT NULL
             AND posted_by IS NOT NULL AND posted_at IS NOT NULL))
);

CREATE TABLE finance_journal_lines (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES finance_journals(id),
    account_id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    debit_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (debit_amount >= 0),
    credit_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (credit_amount >= 0),
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_finance_journal_line_one_side
        CHECK ((debit_amount > 0 AND credit_amount = 0)
            OR (credit_amount > 0 AND debit_amount = 0)),
    CONSTRAINT fk_finance_journal_line_account
        FOREIGN KEY (account_id, organisation_id, currency)
        REFERENCES finance_accounts(id, organisation_id, currency)
);

CREATE INDEX ix_finance_journals_org_scope_date
    ON finance_journals (organisation_id, scope_unit_id, journal_date DESC);
CREATE INDEX ix_finance_journals_status_date
    ON finance_journals (organisation_id, status, journal_date DESC);
CREATE INDEX ix_finance_journal_lines_journal
    ON finance_journal_lines (journal_id);

CREATE FUNCTION enforce_finance_journal_line_draft()
RETURNS TRIGGER AS $$
DECLARE
    target_journal_id UUID;
    target_status VARCHAR(16);
BEGIN
    target_journal_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_id ELSE NEW.journal_id END;
    SELECT status INTO target_status
    FROM finance_journals WHERE id = target_journal_id FOR UPDATE;

    IF target_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Journal lines can only be changed while the journal is a draft'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_journal_lines_draft_only
    BEFORE INSERT OR UPDATE OR DELETE ON finance_journal_lines
    FOR EACH ROW EXECUTE FUNCTION enforce_finance_journal_line_draft();

CREATE FUNCTION enforce_finance_journal_transition()
RETURNS TRIGGER AS $$
DECLARE
    line_count BIGINT;
    debit_total NUMERIC(14,2);
    credit_total NUMERIC(14,2);
BEGIN
    IF OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'Posted financial journals are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'POSTED' AND OLD.status <> 'POSTED' THEN
        IF OLD.status <> 'APPROVED' THEN
            RAISE EXCEPTION 'Only approved journals can be posted'
                USING ERRCODE = '23514';
        END IF;

        SELECT COUNT(*), COALESCE(SUM(debit_amount), 0), COALESCE(SUM(credit_amount), 0)
        INTO line_count, debit_total, credit_total
        FROM finance_journal_lines
        WHERE journal_id = NEW.id AND organisation_id = NEW.organisation_id
          AND currency = NEW.currency;

        IF line_count < 2 OR debit_total <= 0 OR debit_total <> credit_total THEN
            RAISE EXCEPTION 'A journal requires at least two lines and equal positive debit and credit totals'
                USING ERRCODE = '23514';
        END IF;

        NEW.posted_at := COALESCE(NEW.posted_at, CURRENT_TIMESTAMP);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_journals_transition
    BEFORE UPDATE ON finance_journals
    FOR EACH ROW EXECUTE FUNCTION enforce_finance_journal_transition();

CREATE FUNCTION prevent_finance_journal_delete()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'Posted financial journals cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_finance_journals_no_posted_delete
    BEFORE DELETE ON finance_journals
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_journal_delete();
