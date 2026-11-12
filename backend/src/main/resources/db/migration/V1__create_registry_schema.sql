CREATE TABLE organisations (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE branches (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    district_name VARCHAR(160),
    country_code CHAR(2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_branches_org_code UNIQUE (organisation_id, code)
);

CREATE TABLE classifications (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisations(id),
    code VARCHAR(60) NOT NULL,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_classifications_org_code UNIQUE (organisation_id, code)
);

CREATE TABLE members (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL REFERENCES branches(id),
    zcc_app_number VARCHAR(80),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    classification_id UUID REFERENCES classifications(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_members_zcc_app_number
    ON members (zcc_app_number)
    WHERE zcc_app_number IS NOT NULL AND BTRIM(zcc_app_number) <> '';

CREATE INDEX ix_branches_organisation ON branches (organisation_id);
CREATE INDEX ix_classifications_organisation ON classifications (organisation_id);
CREATE INDEX ix_members_branch ON members (branch_id);
CREATE INDEX ix_members_name ON members (branch_id, last_name, first_name);
