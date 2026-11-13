-- Correct the geographical hierarchy and introduce households as a separate domain entity.
-- V2 is already applied in deployed databases; do not modify its released contents.

ALTER TABLE organisational_units
    DROP CONSTRAINT organisational_units_level_check;

ALTER TABLE organisational_units
    ADD CONSTRAINT ck_organisational_units_level
    CHECK (level IN (
        'COUNTRY', 'PROVINCE_STATE', 'DISTRICT', 'NYIKA', 'TABHERA', 'CELL'
    ));

ALTER TABLE organisational_units
    ADD CONSTRAINT uq_organisational_units_id_org_level
    UNIQUE (id, organisation_id, level);

-- The V2 member location extension was transitional. Membership is now linked
-- through the household model; the existing branch register remains intact.
DROP INDEX ix_members_organisational_unit;

ALTER TABLE members
    DROP CONSTRAINT ck_members_single_location,
    DROP CONSTRAINT fk_members_organisational_unit,
    DROP COLUMN organisational_unit_id,
    ALTER COLUMN branch_id SET NOT NULL;

-- A household is a family unit within a geographical Cell, not a hierarchy level.
CREATE TABLE households (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    cell_id UUID NOT NULL,
    cell_level VARCHAR(32) NOT NULL DEFAULT 'CELL' CHECK (cell_level = 'CELL'),
    code VARCHAR(60) NOT NULL,
    name VARCHAR(160) NOT NULL,
    household_head_member_id UUID REFERENCES members(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_households_cell
        FOREIGN KEY (cell_id, organisation_id, cell_level)
        REFERENCES organisational_units (id, organisation_id, level),
    CONSTRAINT uq_households_id_org UNIQUE (id, organisation_id)
);

CREATE UNIQUE INDEX uq_households_cell_code
    ON households (organisation_id, cell_id, code);

CREATE INDEX ix_households_cell
    ON households (organisation_id, cell_id, name);

-- Membership rows are retained when a person moves household; close the old row
-- by setting valid_to before inserting a new active membership.
CREATE TABLE member_household_memberships (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    household_id UUID NOT NULL,
    member_id UUID NOT NULL REFERENCES members(id),
    relationship_label VARCHAR(80) NOT NULL,
    valid_from DATE NOT NULL DEFAULT CURRENT_DATE,
    valid_to DATE,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_membership_household
        FOREIGN KEY (household_id, organisation_id)
        REFERENCES households (id, organisation_id),
    CONSTRAINT ck_membership_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE UNIQUE INDEX uq_member_one_active_household
    ON member_household_memberships (member_id)
    WHERE valid_to IS NULL;

CREATE INDEX ix_household_memberships_household
    ON member_household_memberships (household_id, valid_to);
