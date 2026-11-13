-- Restore required system roles and permissions if their rows were removed.
-- This is a forward-only recovery migration; previously applied migrations remain unchanged.

INSERT INTO admin_permissions (permission_code, name, description) VALUES
('ORG_VIEW', 'View organisation structure', 'View permitted organisation and geographical records'),
('ORG_MANAGE', 'Manage organisation structure', 'Create and maintain permitted geographical records'),
('MEMBERS_VIEW', 'View membership', 'View member and household records within assigned scope'),
('MEMBERS_MANAGE', 'Manage membership', 'Create and maintain member and household records within assigned scope'),
('ADMIN_MANAGE', 'Manage administrators', 'Create, disable and assign administrator accounts'),
('ROLE_CONFIGURE', 'Configure roles', 'Create roles and configure their permissions'),
('FINANCE_CAPTURE', 'Capture financial records', 'Capture financial transactions within assigned scope'),
('FINANCE_RECONCILE', 'Reconcile financial records', 'Record deposits and reconcile financial records within assigned scope'),
('AUDIT_VIEW', 'View audit records', 'Review audit history and exceptions within assigned scope')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_roles (id, role_code, name, description, system_role) VALUES
('10000000-0000-0000-0000-000000000001', 'CENTRAL_ADMIN', 'Central Administrator', 'Organisation-wide administration', TRUE),
('10000000-0000-0000-0000-000000000002', 'DISTRICT_ADMIN', 'District Administrator', 'Administration within an assigned District', TRUE),
('10000000-0000-0000-0000-000000000003', 'NYIKA_ADMIN', 'Nyika Administrator', 'Administration within an assigned Nyika', TRUE),
('10000000-0000-0000-0000-000000000004', 'TABHERA_ADMIN', 'Tabhera Administrator', 'Administration within an assigned Tabhera', TRUE),
('10000000-0000-0000-0000-000000000005', 'CELL_ADMIN', 'Cell Administrator', 'Membership administration within an assigned Cell', TRUE),
('10000000-0000-0000-0000-000000000006', 'FINANCIAL_OFFICER', 'Financial Officer', 'Financial capture within an assigned scope', TRUE),
('10000000-0000-0000-0000-000000000007', 'AUDITOR', 'Auditor', 'Read-only audit access within an assigned scope', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
SELECT '10000000-0000-0000-0000-000000000001', permission_code
FROM admin_permissions
ON CONFLICT (role_id, permission_code) DO NOTHING;

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
('10000000-0000-0000-0000-000000000007', 'AUDIT_VIEW')
ON CONFLICT (role_id, permission_code) DO NOTHING;
