-- Contribution receipts may generate at most one source-linked journal per organisation.
CREATE UNIQUE INDEX uq_finance_journal_contribution_receipt_source
    ON finance_journals (organisation_id, source_id)
    WHERE source_type = 'CONTRIBUTION_RECEIPT' AND source_id IS NOT NULL;
