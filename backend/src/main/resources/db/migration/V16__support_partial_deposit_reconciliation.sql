-- Allow a submitted deposit batch to be reconciled against multiple bank credits.
-- Each statement entry remains one-to-one with a reconciliation record; a batch may have many.

ALTER TABLE deposit_batches
    DROP CONSTRAINT IF EXISTS deposit_batches_status_check;

ALTER TABLE deposit_batches
    ADD CONSTRAINT deposit_batches_status_check
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_RECONCILED', 'RECONCILED', 'EXCEPTION'));

ALTER TABLE deposit_reconciliations
    DROP CONSTRAINT IF EXISTS deposit_reconciliations_deposit_batch_id_key;

CREATE INDEX ix_deposit_reconciliations_batch_matched
    ON deposit_reconciliations (deposit_batch_id, matched_at DESC);
