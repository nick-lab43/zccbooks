-- Household-level contributions retain the household head as the member attribution
-- required by the existing receipt, deposit and ledger workflows.
ALTER TABLE contributions
    ADD COLUMN household_id UUID;

ALTER TABLE contributions
    ADD CONSTRAINT fk_contributions_household_org
    FOREIGN KEY (household_id, organisation_id)
    REFERENCES households (id, organisation_id);

CREATE INDEX ix_contributions_household_date
    ON contributions (household_id, contribution_date DESC)
    WHERE household_id IS NOT NULL;

CREATE FUNCTION enforce_contribution_household_head()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.household_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM households h
        JOIN members m
          ON m.id = NEW.member_id
         AND m.organisation_id = NEW.organisation_id
         AND m.cell_id = h.cell_id
         AND m.active = TRUE
        JOIN member_household_memberships hm
          ON hm.household_id = h.id
         AND hm.organisation_id = h.organisation_id
         AND hm.member_id = m.id
         AND hm.valid_to IS NULL
        WHERE h.id = NEW.household_id
          AND h.organisation_id = NEW.organisation_id
          AND h.active = TRUE
          AND h.household_head_member_id = m.id
    ) THEN
        RAISE EXCEPTION 'Household contributions must be attributed to the active designated household head'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_contributions_household_head
    BEFORE INSERT OR UPDATE OF household_id, member_id, organisation_id
    ON contributions
    FOR EACH ROW EXECUTE FUNCTION enforce_contribution_household_head();
