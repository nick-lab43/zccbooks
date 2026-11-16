CREATE TABLE expenditure_evidence (
    id UUID PRIMARY KEY,
    expenditure_id UUID NOT NULL REFERENCES expenditures(id),
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png', 'image/webp')),
    file_size BIGINT NOT NULL CHECK (file_size > 0 AND file_size <= 10485760),
    sha256 CHAR(64) NOT NULL,
    file_data BYTEA NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES admin_users(id),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_expenditure_evidence_digest UNIQUE (expenditure_id, sha256)
);

CREATE INDEX ix_expenditure_evidence_expense_uploaded
    ON expenditure_evidence (expenditure_id, uploaded_at, id);
