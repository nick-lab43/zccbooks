CREATE TABLE deposit_evidence (
    id UUID PRIMARY KEY,
    deposit_batch_id UUID NOT NULL REFERENCES deposit_batches(id),
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0 AND file_size <= 10485760),
    sha256 CHAR(64) NOT NULL,
    file_data BYTEA NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES admin_users(id),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_deposit_evidence_batch_digest UNIQUE (deposit_batch_id, sha256)
);

CREATE INDEX idx_deposit_evidence_batch_uploaded
    ON deposit_evidence (deposit_batch_id, uploaded_at DESC);
