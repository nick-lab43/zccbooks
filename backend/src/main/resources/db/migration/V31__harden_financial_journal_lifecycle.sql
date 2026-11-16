-- Harden non-draft journal headers and lifecycle metadata against direct mutation.

CREATE OR REPLACE FUNCTION enforce_finance_journal_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'Posted financial journals are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.organisation_id IS DISTINCT FROM OLD.organisation_id
       OR NEW.scope_unit_id IS DISTINCT FROM OLD.scope_unit_id
       OR NEW.journal_number IS DISTINCT FROM OLD.journal_number
       OR NEW.journal_date IS DISTINCT FROM OLD.journal_date
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_id IS DISTINCT FROM OLD.source_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Submitted financial journal details are immutable'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED') OR
        (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED', 'REJECTED')) OR
        (OLD.status = 'APPROVED' AND NEW.status = 'POSTED')
    ) THEN
        RAISE EXCEPTION 'Invalid financial journal status transition'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
       AND NOT (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED') THEN
        RAISE EXCEPTION 'Submission timestamp can only be set during submission'
            USING ERRCODE = '23514';
    END IF;

    IF (NEW.approved_by IS DISTINCT FROM OLD.approved_by
        OR NEW.approved_at IS DISTINCT FROM OLD.approved_at)
       AND NOT (OLD.status = 'SUBMITTED' AND NEW.status = 'APPROVED') THEN
        RAISE EXCEPTION 'Approval metadata can only be set during approval'
            USING ERRCODE = '23514';
    END IF;

    IF (NEW.posted_by IS DISTINCT FROM OLD.posted_by
        OR NEW.posted_at IS DISTINCT FROM OLD.posted_at)
       AND NOT (OLD.status = 'APPROVED' AND NEW.status = 'POSTED') THEN
        RAISE EXCEPTION 'Posting metadata can only be set during posting'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'APPROVED' AND
       (NEW.approved_by IS NULL OR NEW.approved_at IS NULL OR NEW.approved_by = NEW.created_by) THEN
        RAISE EXCEPTION 'Approval requires a different administrator from the journal creator'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'POSTED' AND
       (NEW.posted_by IS NULL OR NEW.posted_at IS NULL
         OR NEW.posted_by = NEW.approved_by OR NEW.posted_by = NEW.created_by) THEN
        RAISE EXCEPTION 'Posting requires an administrator different from the creator and approver'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'POSTED' AND OLD.status <> 'POSTED' THEN
        IF OLD.status <> 'APPROVED' THEN
            RAISE EXCEPTION 'Only approved journals can be posted'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM finance_journal_lines
            WHERE journal_id = NEW.id AND organisation_id = NEW.organisation_id
              AND currency = NEW.currency
            GROUP BY journal_id
            HAVING COUNT(*) >= 2
               AND SUM(debit_amount) > 0
               AND SUM(debit_amount) = SUM(credit_amount)
        ) THEN
            RAISE EXCEPTION 'A journal requires at least two lines and equal positive debit and credit totals'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
