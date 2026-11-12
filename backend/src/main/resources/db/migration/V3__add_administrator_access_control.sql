CREATE TABLE admin_users (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_admin_users_username_ci ON admin_users (LOWER(username));

CREATE TABLE admin_roles (
    id UUID PRIMARY KEY,
    role_code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(300),
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE admin_permissions (
    permission_code VARCHAR(60) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(300) NOT NULL
);

CREATE TABLE admin_role_permissions (
    role_id UUID NOT NULL REFERENCES admin_roles(id) ON DELETE CASCADE,
    permission_code VARCHAR(60) NOT NULL REFERENCES admin_permissions(permission_code),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE admin_role_assignments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES admin_users(id),
    role_id UUID NOT NULL REFERENCES admin_roles(id),
    scope_type VARCHAR(20) NOT NULL CHECK (scope_type IN ('GLOBAL', 'ORGANISATION', 'UNIT')),
    organisation_id UUID REFERENCES organisations(id),
    scope_unit_id UUID,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_admin_assignment_scope CHECK (
        (scope_type = 'GLOBAL' AND organisation_id IS NULL AND scope_unit_id IS NULL)
        OR (scope_type = 'ORGANISATION' AND organisation_id IS NOT NULL AND scope_unit_id IS NULL)
        OR (scope_type = 'UNIT' AND organisation_id IS NOT NULL AND scope_unit_id IS NOT NULL)
    ),
    CONSTRAINT fk_admin_assignment_unit
        FOREIGN KEY (scope_unit_id, organisation_id)
        REFERENCES organisational_units (id, organisation_id)
);

CREATE UNIQUE INDEX uq_admin_assignment_global
    ON admin_role_assignments (user_id, role_id)
    WHERE scope_type = 'GLOBAL' AND revoked_at IS NULL;
CREATE UNIQUE INDEX uq_admin_assignment_organisation
    ON admin_role_assignments (user_id, role_id, organisation_id)
    WHERE scope_type = 'ORGANISATION' AND revoked_at IS NULL;
CREATE UNIQUE INDEX uq_admin_assignment_unit
    ON admin_role_assignments (user_id, role_id, scope_unit_id)
    WHERE scope_type = 'UNIT' AND revoked_at IS NULL;

CREATE INDEX ix_admin_role_assignments_user ON admin_role_assignments (user_id);
CREATE INDEX ix_admin_role_permissions_permission ON admin_role_permissions (permission_code);

INSERT INTO admin_permissions (permission_code, name, description) VALUES
('ORG_VIEW', 'View organisation structure', 'View permitted organisation and geographical records'),
('ORG_MANAGE', 'Manage organisation structure', 'Create and maintain permitted geographical records'),
('MEMBERS_VIEW', 'View membership', 'View member and household records within assigned scope'),
('MEMBERS_MANAGE', 'Manage membership', 'Create and maintain member and household records within assigned scope'),
('ADMIN_MANAGE', 'Manage administrators', 'Create, disable and assign administrator accounts'),
('ROLE_CONFIGURE', 'Configure roles', 'Create roles and configure their permissions'),
('FINANCE_CAPTURE', 'Capture financial records', 'Capture financial transactions within assigned scope'),
('FINANCE_RECONCILE', 'Reconcile financial records', 'Record deposits and reconcile financial records within assigned scope'),
('AUDIT_VIEW', 'View audit records', 'Review audit history and exceptions within assigned scope');

INSERT INTO admin_roles (id, role_code, name, description, system_role) VALUES
('10000000-0000-0000-0000-000000000001', 'CENTRAL_ADMIN', 'Central Administrator', 'Organisation-wide administration', TRUE),
('10000000-0000-0000-0000-000000000002', 'DISTRICT_ADMIN', 'District Administrator', 'Administration within an assigned District', TRUE),
('10000000-0000-0000-0000-000000000003', 'NYIKA_ADMIN', 'Nyika Administrator', 'Administration within an assigned Nyika', TRUE),
('10000000-0000-0000-0000-000000000004', 'TABHERA_ADMIN', 'Tabhera Administrator', 'Administration within an assigned Tabhera', TRUE),
('10000000-0000-0000-0000-000000000005', 'CELL_ADMIN', 'Cell Administrator', 'Membership administration within an assigned Cell', TRUE),
('10000000-0000-0000-0000-000000000006', 'FINANCIAL_OFFICER', 'Financial Officer', 'Financial capture within an assigned scope', TRUE),
('10000000-0000-0000-0000-000000000007', 'AUDITOR', 'Auditor', 'Read-only audit access within an assigned scope', TRUE);

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT '10000000-0000-0000-0000-000000000001', permission_code FROM admin_permissions;

INSERT INTO admin_role_permissions (role_id, permission_code) VALUES
('10000000-0000-0000-0000-000000000002', 'ORG_VIEW'),
('10000000-0000-0000-0000-000000000002', 'ORG_MANAGE'),
('10000000-0000-0000-0000-000000000002', 'MEMBERS_VIEW'),
('10000000-0000-0000-0000-000000000002', 'MEMBERS_MANAGE'),
('10000000-0000-0000-0000-000000000003', 'ORG_VIEW'),
('10000000-0000-0000-0000-000000000003', 'ORG_MANAGE'),
('10000000-0000-0000-0000-000000000003', 'MEMBERS_VIEW'),
('10000000-0000-0000-0000-000000000003', 'MEMBERS_MANAGE'),
('10000000-0000-0000-0000-000000000004', 'ORG_VIEW'),
('10000000-0000-0000-0000-000000000004', 'MEMBERS_VIEW'),
('10000000-0000-0000-0000-000000000004', 'MEMBERS_MANAGE'),
('10000000-0000-0000-0000-000000000005', 'MEMBERS_VIEW'),
('10000000-0000-0000-0000-000000000005', 'MEMBERS_MANAGE'),
('10000000-0000-0000-0000-000000000006', 'FINANCE_CAPTURE'),
('10000000-0000-0000-0000-000000000006', 'FINANCE_RECONCILE'),
('10000000-0000-0000-0000-000000000007', 'ORG_VIEW'),
('10000000-0000-0000-0000-000000000007', 'MEMBERS_VIEW'),
('10000000-0000-0000-0000-000000000007', 'AUDIT_VIEW');

-- Administrative events are append-only; no update/delete API is exposed.
CREATE TABLE admin_audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES admin_users(id),
    action_code VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_admin_audit_events_occurred ON admin_audit_events (occurred_at DESC);
CREATE INDEX ix_admin_audit_events_actor ON admin_audit_events (actor_user_id, occurred_at DESC);
