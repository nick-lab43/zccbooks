CREATE TABLE deposit_ocr_reviews (
    id UUID PRIMARY KEY,
    deposit_batch_id UUID NOT NULL REFERENCES deposit_batches(id),
    evidence_id UUID NOT NULL REFERENCES deposit_evidence(id),
    reviewed_amount NUMERIC(14,2) NOT NULL CHECK (reviewed_amount > 0),
    reviewed_bank_reference VARCHAR(120) NOT NULL,
    reviewed_deposit_date DATE NOT NULL,
    ocr_amount_candidate VARCHAR(80),
    ocr_reference_candidate VARCHAR(120),
    ocr_date_candidate VARCHAR(40),
    amount_matches BOOLEAN NOT NULL,
    reference_matches BOOLEAN NOT NULL,
    date_matches BOOLEAN NOT NULL,
    operator_confirmed_original BOOLEAN NOT NULL CHECK (operator_confirmed_original = TRUE),
    reviewed_by UUID NOT NULL REFERENCES admin_users(id),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_deposit_ocr_reviews_batch_reviewed
    ON deposit_ocr_reviews (deposit_batch_id, reviewed_at DESC);


CREATE FUNCTION prevent_deposit_ocr_review_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Deposit OCR review history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_deposit_ocr_reviews_immutable
    BEFORE UPDATE OR DELETE ON deposit_ocr_reviews
    FOR EACH ROW EXECUTE FUNCTION prevent_deposit_ocr_review_mutation();
