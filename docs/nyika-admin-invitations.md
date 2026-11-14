# Nyika administrator invitations

Central Administration can invite an administrator for an active Nyika from **Users & access**.

## Invitation behaviour

- The API accepts an active Nyika ID, administrator display name and email address.
- The server derives the username from the Nyika code (for example, `seshego_admin`) and appends a numeric suffix when necessary.
- The server assigns the fixed `NYIKA_ADMIN` system role at that Nyika's unit scope. The requester cannot choose the role or scope.
- The account is disabled until the invitation is accepted.
- Invitation tokens contain 256 bits of cryptographically secure randomness. Only a SHA-256 hash is stored.
- Links expire after 48 hours and are consumed atomically once. Revoked, expired and previously accepted links cannot activate an account.
- Invitation creation, acceptance and revocation are audited.

## Email configuration

Configure these environment variables on the backend deployment:

| Variable | Purpose |
| --- | --- |
| `INVITATION_EMAIL_ENABLED` | Set to `true` to enable delivery |
| `SMTP_HOST` | SMTP server hostname |
| `SMTP_PORT` | SMTP port (normally 587 for STARTTLS) |
| `SMTP_USERNAME` | SMTP account username |
| `SMTP_PASSWORD` | SMTP account password or provider app password |
| `SMTP_AUTH` | SMTP authentication; normally `true` |
| `SMTP_STARTTLS` | STARTTLS; normally `true` |
| `MAIL_FROM` | Verified sender address |
| `FRONTEND_URL` | Public Vercel frontend origin, without a trailing slash |
| `FRONTEND_ORIGIN` | Allowed browser origin for API CORS |

Do not enable invitation delivery until valid SMTP credentials and a verified sender address are configured. If mail delivery is disabled, invitation creation fails without creating an account or invitation.

The frontend activation route is `/accept-invitation?token=...`. The invitee sets a password of at least 12 characters, then signs in with the username in the email.

## Deployment notes

Flyway applies migration `V8__nyika_administrator_invitations.sql` automatically. Deploy the backend before using the invitation form. Configure the frontend URL and SMTP settings on the backend service, then redeploy it.
