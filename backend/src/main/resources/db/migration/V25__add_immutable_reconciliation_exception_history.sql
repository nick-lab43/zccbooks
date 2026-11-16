CREATE TABLE reconciliation_exception_history (
    id UUID PRIMARY KEY,
    exception_id UUID NOT NULL REFERENCES reconciliation_exceptions(id),
    previous_status VARCHAR(20) CHECK (previous_status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED')),
    new_status VARCHAR(20) NOT NULL CHECK (new_status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED')),
    action VARCHAR(20) NOT NULL CHECK (action IN ('BASELINE', 'CREATED', 'STATUS_CHANGED')),
    note VARCHAR(1000),
    changed_by UUID REFERENCES admin_users(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_reconciliation_exception_history_action CHECK (
        (action = 'BASELINE' AND previous_status IS NULL)
        OR (action = 'CREATED' AND previous_status IS NULL AND new_status = 'OPEN' AND changed_by IS NOT NULL)
        OR (action = 'STATUS_CHANGED' AND previous_status IS NOT NULL AND previous_status <> new_status AND changed_by IS NOT NULL)
    )
);

INSERT INTO reconciliation_exception_history
    (id, exception_id, previous_status, new_status, action, note, changed_by, changed_at)
SELECT gen_random_uuid(), id, NULL, status, 'BASELINE', resolution_note, updated_by, updated_at
FROM reconciliation_exceptions;

CREATE INDEX ix_reconciliation_exception_history_exception_changed
    ON reconciliation_exception_history (exception_id, changed_at DESC);

CREATE FUNCTION prevent_reconciliation_exception_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Reconciliation exception history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reconciliation_exception_history_immutable
    BEFORE UPDATE OR DELETE ON reconciliation_exception_history
    FOR EACH ROW EXECUTE FUNCTION prevent_reconciliation_exception_history_mutation();
