-- Centrally governed church positions and date-effective member appointments.
-- Position records are catalogue data; appointment records preserve assignment history.

CREATE TABLE church_positions (
    id UUID PRIMARY KEY,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(300),
    allow_concurrent BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_church_positions_code_ci
    ON church_positions (LOWER(code));

CREATE TABLE member_appointments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    member_id UUID NOT NULL,
    position_id UUID NOT NULL REFERENCES church_positions(id),
    scope_unit_id UUID NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    note VARCHAR(1000),
    recorded_by UUID NOT NULL REFERENCES admin_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_member_appointment_member
        FOREIGN KEY (member_id, organisation_id)
        REFERENCES members (id, organisation_id),
    CONSTRAINT fk_member_appointment_scope
        FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units (id, organisation_id),
    CONSTRAINT ck_member_appointment_dates
        CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX ix_member_appointments_member
    ON member_appointments (organisation_id, member_id, valid_from DESC);

CREATE INDEX ix_member_appointments_scope
    ON member_appointments (organisation_id, scope_unit_id, valid_from DESC);

CREATE INDEX ix_member_appointments_position
    ON member_appointments (position_id, valid_from DESC);
