-- Personal contact details and dates of birth require a narrower permission
-- than general membership visibility. Membership managers retain access;
-- read-only auditors do not receive access to these fields.
INSERT INTO admin_permissions (permission_code, name, description)
VALUES ('MEMBER_PII_VIEW', 'View member personal information',
        'View member contact details and dates of birth within assigned scope')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_id, permission_code)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'MEMBER_PII_VIEW'),
    ('10000000-0000-0000-0000-000000000002', 'MEMBER_PII_VIEW'),
    ('10000000-0000-0000-0000-000000000003', 'MEMBER_PII_VIEW'),
    ('10000000-0000-0000-0000-000000000004', 'MEMBER_PII_VIEW'),
    ('10000000-0000-0000-0000-000000000005', 'MEMBER_PII_VIEW')
ON CONFLICT (role_id, permission_code) DO NOTHING;
