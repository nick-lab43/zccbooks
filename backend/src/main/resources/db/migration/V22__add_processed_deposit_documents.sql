CREATE TABLE deposit_documents (
    id UUID PRIMARY KEY,
    deposit_batch_id UUID NOT NULL REFERENCES deposit_batches(id),
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0 AND file_size <= 104857600),
    sha256 CHAR(64) NOT NULL,
    file_data BYTEA NOT NULL,
    page_count INTEGER NOT NULL CHECK (page_count BETWEEN 1 AND 10),
    created_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_deposit_document_batch_digest UNIQUE (deposit_batch_id, sha256)
);

CREATE TABLE deposit_document_sources (
    document_id UUID NOT NULL REFERENCES deposit_documents(id),
    evidence_id UUID NOT NULL REFERENCES deposit_evidence(id),
    page_number INTEGER NOT NULL CHECK (page_number BETWEEN 1 AND 10),
    PRIMARY KEY (document_id, evidence_id),
    UNIQUE (document_id, page_number)
);

CREATE INDEX ix_deposit_documents_batch_created
    ON deposit_documents (deposit_batch_id, created_at DESC);
