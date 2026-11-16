-- Central review of submitted deposit packages, separate from bank reconciliation.
INSERT INTO admin_permissions (permission_code, name, description)
VALUES ('FINANCE_REVIEW', 'Review deposit packages', 'Accept, query or reject submitted deposit packages within central financial administration')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT r.id, 'FINANCE_REVIEW'
FROM admin_roles r
WHERE r.role_code = 'CENTRAL_ADMIN'
ON CONFLICT DO NOTHING;

ALTER TABLE deposit_batches
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (review_status IN ('NOT_SUBMITTED', 'PENDING_REVIEW', 'QUERIED', 'REJECTED', 'ACCEPTED')),
    ADD COLUMN review_response_note VARCHAR(1000);

UPDATE deposit_batches
SET review_status = CASE
    WHEN status = 'RECONCILED' THEN 'ACCEPTED'
    WHEN status IN ('SUBMITTED', 'PARTIALLY_RECONCILED') THEN 'PENDING_REVIEW'
    ELSE 'NOT_SUBMITTED'
END;

CREATE TABLE deposit_review_history (
    id UUID PRIMARY KEY,
    deposit_batch_id UUID NOT NULL REFERENCES deposit_batches(id),
    action VARCHAR(20) NOT NULL CHECK (action IN ('BASELINE', 'SUBMITTED', 'ACCEPTED', 'QUERIED', 'REJECTED', 'RESPONDED')),
    previous_status VARCHAR(20) CHECK (previous_status IN ('NOT_SUBMITTED', 'PENDING_REVIEW', 'QUERIED', 'REJECTED', 'ACCEPTED')),
    new_status VARCHAR(20) NOT NULL CHECK (new_status IN ('NOT_SUBMITTED', 'PENDING_REVIEW', 'QUERIED', 'REJECTED', 'ACCEPTED')),
    note VARCHAR(1000),
    changed_by UUID REFERENCES admin_users(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO deposit_review_history (id, deposit_batch_id, action, previous_status, new_status, note, changed_by, changed_at)
SELECT gen_random_uuid(), id, 'BASELINE', NULL, review_status, 'Review status established during rollout', NULL, CURRENT_TIMESTAMP
FROM deposit_batches;

CREATE INDEX ix_deposit_batches_review_queue
    ON deposit_batches (organisation_id, review_status, submitted_at DESC);

CREATE INDEX ix_deposit_review_history_batch
    ON deposit_review_history (deposit_batch_id, changed_at DESC);

CREATE FUNCTION prevent_deposit_review_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Deposit review history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_deposit_review_history_immutable
    BEFORE UPDATE OR DELETE ON deposit_review_history
    FOR EACH ROW EXECUTE FUNCTION prevent_deposit_review_history_mutation();
