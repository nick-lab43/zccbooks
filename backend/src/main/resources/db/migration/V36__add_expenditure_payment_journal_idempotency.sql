-- Idempotent source identity for expenditure payment recognition.
CREATE UNIQUE INDEX uq_finance_journal_expenditure_payment_source
    ON finance_journals (organisation_id, source_id)
    WHERE source_type = 'EXPENDITURE_PAYMENT' AND source_id IS NOT NULL;
