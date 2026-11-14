-- Introduce canonical Cell placement while preserving legacy branch records.
-- V1-V5 are deployed and immutable; this migration is additive/forward-only.

ALTER TABLE members
    ADD COLUMN organisation_id UUID,
    ADD COLUMN cell_id UUID,
    ADD COLUMN cell_level VARCHAR(32) NOT NULL DEFAULT 'CELL'
        CHECK (cell_level = 'CELL');

UPDATE members m
SET organisation_id = b.organisation_id
FROM branches b
WHERE b.id = m.branch_id;

ALTER TABLE members
    ALTER COLUMN organisation_id SET NOT NULL,
    ALTER COLUMN branch_id DROP NOT NULL,
    ADD CONSTRAINT uq_members_id_organisation UNIQUE (id, organisation_id),
    ADD CONSTRAINT fk_members_organisation
        FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    ADD CONSTRAINT fk_members_cell
        FOREIGN KEY (cell_id, organisation_id, cell_level)
        REFERENCES organisational_units (id, organisation_id, level),
    ADD CONSTRAINT ck_members_single_location
        CHECK (
            (branch_id IS NOT NULL AND cell_id IS NULL)
            OR
            (branch_id IS NULL AND cell_id IS NOT NULL)
        );

CREATE INDEX ix_members_cell ON members (organisation_id, cell_id, last_name, first_name);
CREATE INDEX ix_members_organisation ON members (organisation_id);

-- Replace the single-column member FK with an organisation-aware FK.
ALTER TABLE member_household_memberships
    DROP CONSTRAINT member_household_memberships_member_id_fkey,
    ADD CONSTRAINT fk_household_membership_member_org
        FOREIGN KEY (member_id, organisation_id)
        REFERENCES members (id, organisation_id);

-- A household membership must be for a member whose current geographical
-- placement is the same Cell as the household.
CREATE OR REPLACE FUNCTION enforce_household_member_cell()
RETURNS TRIGGER AS $$
DECLARE
    household_cell UUID;
    member_cell UUID;
BEGIN
    SELECT cell_id INTO household_cell
    FROM households
    WHERE id = NEW.household_id AND organisation_id = NEW.organisation_id;

    SELECT cell_id INTO member_cell
    FROM members
    WHERE id = NEW.member_id AND organisation_id = NEW.organisation_id;

    IF household_cell IS NULL OR member_cell IS NULL OR household_cell <> member_cell THEN
        RAISE EXCEPTION 'Member must be assigned to the same Cell as the household'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_household_member_cell
BEFORE INSERT OR UPDATE OF household_id, member_id, organisation_id
ON member_household_memberships
FOR EACH ROW EXECUTE FUNCTION enforce_household_member_cell();

-- A household head must be an active member of that same household.
CREATE OR REPLACE FUNCTION enforce_household_head_membership()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.household_head_member_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM member_household_memberships x
        WHERE x.household_id = NEW.id
          AND x.member_id = NEW.household_head_member_id
          AND x.organisation_id = NEW.organisation_id
          AND x.valid_to IS NULL
          AND EXISTS (
              SELECT 1 FROM members m
              WHERE m.id = x.member_id AND m.organisation_id = x.organisation_id AND m.active = TRUE
          )
    ) THEN
        RAISE EXCEPTION 'Household head must have an active membership in this household'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_household_head_membership
BEFORE INSERT OR UPDATE OF household_head_member_id
ON households
FOR EACH ROW EXECUTE FUNCTION enforce_household_head_membership();

-- Prevent ending the active membership of a household head until another head
-- is designated or the current head is explicitly cleared.
CREATE OR REPLACE FUNCTION protect_active_household_head()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.valid_to IS NULL AND NEW.valid_to IS NOT NULL
       AND EXISTS (
           SELECT 1 FROM households h
           WHERE h.id = OLD.household_id
             AND h.household_head_member_id = OLD.member_id
       ) THEN
        RAISE EXCEPTION 'Clear or replace the household head before ending their membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_active_household_head
BEFORE UPDATE OF valid_to
ON member_household_memberships
FOR EACH ROW EXECUTE FUNCTION protect_active_household_head();
