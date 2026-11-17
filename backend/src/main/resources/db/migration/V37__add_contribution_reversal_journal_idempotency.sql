-- Enforce one reversing journal for each immutable contribution reversal event.
CREATE UNIQUE INDEX uq_finance_journal_contribution_reversal_source
    ON finance_journals (organisation_id, source_id)
    WHERE source_type = 'CONTRIBUTION_REVERSAL' AND source_id IS NOT NULL;
