CREATE TABLE deposit_evidence_integrity_checks (
    id UUID PRIMARY KEY,
    check_run_id UUID NOT NULL,
    deposit_batch_id UUID NOT NULL REFERENCES deposit_batches(id),
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('EVIDENCE', 'SCANNED_DOCUMENT')),
    target_id UUID NOT NULL,
    target_filename VARCHAR(255) NOT NULL,
    stored_sha256 CHAR(64) NOT NULL,
    computed_sha256 CHAR(64) NOT NULL,
    integrity_verified BOOLEAN NOT NULL,
    checked_by UUID NOT NULL REFERENCES admin_users(id),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_deposit_evidence_integrity_batch_checked
    ON deposit_evidence_integrity_checks (deposit_batch_id, checked_at DESC);

CREATE INDEX ix_deposit_evidence_integrity_run
    ON deposit_evidence_integrity_checks (check_run_id, target_type, target_id);

CREATE FUNCTION prevent_deposit_evidence_integrity_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Deposit evidence integrity history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_deposit_evidence_integrity_immutable
    BEFORE UPDATE OR DELETE ON deposit_evidence_integrity_checks
    FOR EACH ROW EXECUTE FUNCTION prevent_deposit_evidence_integrity_mutation();
