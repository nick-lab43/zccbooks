-- Preserve draft amendment concurrency and allow auditable withdrawal before submission.
ALTER TABLE expenditures
    DROP CONSTRAINT expenditures_status_check;

ALTER TABLE expenditures
    ADD CONSTRAINT expenditures_status_check
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PAID', 'VOIDED')),
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    ADD COLUMN voided_by UUID REFERENCES admin_users(id),
    ADD COLUMN voided_at TIMESTAMPTZ,
    ADD COLUMN void_reason VARCHAR(500),
    ADD CONSTRAINT ck_expenditure_void_metadata CHECK (
        (status = 'VOIDED' AND voided_by IS NOT NULL AND voided_at IS NOT NULL AND void_reason IS NOT NULL)
        OR
        (status <> 'VOIDED' AND voided_by IS NULL AND voided_at IS NULL AND void_reason IS NULL)
    );
