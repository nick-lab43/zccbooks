ALTER TABLE admin_users ADD COLUMN email VARCHAR(254);
CREATE UNIQUE INDEX uq_admin_users_email_ci ON admin_users (LOWER(email)) WHERE email IS NOT NULL;

CREATE TABLE admin_invitations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES admin_users(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    invited_by UUID NOT NULL REFERENCES admin_users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin_invitation_terminal_state CHECK (NOT (accepted_at IS NOT NULL AND revoked_at IS NOT NULL))
);
CREATE INDEX ix_admin_invitations_user ON admin_invitations(user_id, created_at DESC);
CREATE UNIQUE INDEX uq_admin_invitation_pending_user ON admin_invitations(user_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
