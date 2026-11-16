-- Keep contribution deposit follow-ups aligned with their actual deposit state.
ALTER TABLE contribution_deposit_followups
    DROP CONSTRAINT contribution_deposit_followups_status_check;

ALTER TABLE contribution_deposit_followups
    ALTER COLUMN status TYPE VARCHAR(24),
    ADD CONSTRAINT contribution_deposit_followups_status_check
        CHECK (status IN ('PENDING', 'ACKNOWLEDGED', 'IN_DRAFT', 'SUBMITTED', 'PARTIALLY_BANKED', 'RESOLVED'));
