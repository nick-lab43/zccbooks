CREATE TABLE organisational_units (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    parent_id UUID,
    level VARCHAR(32) NOT NULL CHECK (level IN (
        'COUNTRY', 'PROVINCE_STATE', 'DISTRICT', 'NYIKA',
        'TABHERA', 'CELL', 'TABHERA_YEMUMBA'
    )),
    code VARCHAR(60) NOT NULL,
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_organisational_units_id_org UNIQUE (id, organisation_id),
    CONSTRAINT fk_organisational_units_parent
        FOREIGN KEY (parent_id, organisation_id)
        REFERENCES organisational_units (id, organisation_id)
);

CREATE UNIQUE INDEX uq_organisational_units_root_code
    ON organisational_units (organisation_id, code)
    WHERE parent_id IS NULL;

CREATE UNIQUE INDEX uq_organisational_units_child_code
    ON organisational_units (organisation_id, parent_id, code)
    WHERE parent_id IS NOT NULL;

CREATE INDEX ix_organisational_units_parent
    ON organisational_units (organisation_id, parent_id);

ALTER TABLE members
    ALTER COLUMN branch_id DROP NOT NULL,
    ADD COLUMN organisational_unit_id UUID;

ALTER TABLE members
    ADD CONSTRAINT fk_members_organisational_unit
    FOREIGN KEY (organisational_unit_id) REFERENCES organisational_units(id);

ALTER TABLE members
    ADD CONSTRAINT ck_members_single_location
    CHECK (
        (branch_id IS NOT NULL AND organisational_unit_id IS NULL)
        OR
        (branch_id IS NULL AND organisational_unit_id IS NOT NULL)
    );

CREATE INDEX ix_members_organisational_unit
    ON members (organisational_unit_id);
