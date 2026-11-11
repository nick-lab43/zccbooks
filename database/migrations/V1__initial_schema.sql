CREATE TABLE organisation (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE branch (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation(id),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organisation_id, code)
);

CREATE TABLE member_classification (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation(id),
    code VARCHAR(60) NOT NULL,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (organisation_id, code)
);

CREATE TABLE member (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL REFERENCES branch(id),
    zcc_app_number VARCHAR(80),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    classification_id UUID REFERENCES member_classification(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_member_branch ON member(branch_id);
CREATE INDEX idx_member_app_number ON member(zcc_app_number);