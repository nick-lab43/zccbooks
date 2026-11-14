# ZCC Books

A configurable, multi-branch church finance and audit management platform. The MVP demonstrates branch administration, member registration, contributions, digital receipts, deposits and reconciliation using fictional data.

## Technology

- Frontend: React, TypeScript, Vite and Tailwind CSS
- Backend: Java 21 and Spring Boot
- Database: PostgreSQL 17
- Schema migrations: Flyway

## Run locally

### 1. Start PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

The local database is available on `localhost:5432` with database/user `zccbooks`. The development password is `change-me`; do not reuse it outside local development.

### 2. Start the API

```bash
cd backend
mvn spring-boot:run
```

The API starts at `http://localhost:8080`. Flyway applies database migrations on startup.

### 3. Start the frontend

In a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` requests to the backend.

## Current functionality

- Organisation records and the canonical Country → Province/State → District → Nyika → Tabhera → Cell hierarchy.
- Administrator authentication, role-based permissions, geographical scope enforcement and append-only audit events.
- Cell-based member registration, classifications, household membership history and member contact profiles.
- Centrally governed church positions and date-effective member appointments.
- Financial Administration foundation: configurable financial streams, contribution categories, category applicability by geographical level and period-based financial targets.
- Scoped Financial Officer and Auditor access to financial configuration and target registers.
- Scoped contribution capture for authorised finance roles, sequential receipts, idempotent posting, target achievement calculations and pending-deposit follow-ups.
- Cash deposit batches with full-receipt allocation, submission controls and exact bank-statement reconciliation.

The membership application integration is not connected. Use fictional demonstration records only.

## API overview

- `GET/POST /api/organisations`
- `GET/POST /api/organisations/{organisationId}/units`
- `GET/POST /api/units/{cellId}/members`
- `GET/POST /api/units/{cellId}/households`
- `GET/POST /api/organisations/{organisationId}/financial-streams`
- `GET/POST /api/organisations/{organisationId}/contribution-categories`
- `GET/POST /api/organisations/{organisationId}/financial-targets`
- `GET /api/organisations/{organisationId}/financial-units`
- `POST /api/organisations/{organisationId}/contributions`
- `GET /api/contributions/{contributionId}/receipt`
- `GET /api/organisations/{organisationId}/financial-target-progress`
- `GET /api/organisations/{organisationId}/deposit-followups` and `PATCH /api/deposit-followups/{followupId}`

## Current limitations

Financial configuration, scoped contribution posting, receipt issuance, target achievement calculations and pending-deposit follow-ups are implemented. Bank deposits, deposit batch allocation, document scanning, bank reconciliation, reversals/refunds and independent financial reporting remain future work. Do not use this development build for live financial or member records until hosted verification and the remaining operational controls are completed.

## Cloud deployment (Vercel + Render + Neon)

The hosted frontend and API are separate services. Vercel serves the React application; Render runs the Spring Boot API; Neon hosts PostgreSQL.

### 1. Deploy the API on Render

1. Merge the cloud API deployment pull request into `main`.
2. In Render, create a **Web Service** from this repository and select `main`.
3. Choose **Docker** as the runtime. Leave **Root Directory** blank so Render uses the repository-root `Dockerfile`.
4. Add these environment variables in Render:
   - `DB_URL`: JDBC URL in the form `jdbc:postgresql://YOUR-NEON-HOST/zccbooks?sslmode=require`.
   - `DB_USERNAME`: the Neon database role.
   - `DB_PASSWORD`: the Neon role password.
   - `FRONTEND_ORIGIN`: the exact public Vercel origin, for example `https://your-project.vercel.app` (no trailing slash).
5. Deploy. Flyway creates the registry tables on first startup. Confirm the service health at `https://YOUR-RENDER-SERVICE.onrender.com/api/health`.

Use the Neon **direct** connection details. Keep credentials in Render environment variables; never commit them or put them in Vercel.

### 2. Point Vercel at the API

In the Vercel project settings, add this environment variable for Production (and Preview if required):

- `VITE_API_BASE_URL`: `https://YOUR-RENDER-SERVICE.onrender.com`

Redeploy the frontend after saving the variable. The Vite development proxy remains available for local development.

### Deployment notes

- Render's `PORT` environment variable is honoured by the API.
- The API allows browser requests only from the configured `FRONTEND_ORIGIN`.
- The current release remains a development foundation. Authentication, role permissions and geographical-scope checks are enforced by the backend, but operational security, financial controls and hosted workflows still require verification. Do not enter live member or financial data.


## Administrator access and deployment bootstrap

The API now requires administrator authentication for every endpoint except `GET /api/health`. Authentication uses HTTP Basic over HTTPS, with BCrypt password hashes stored in PostgreSQL. The frontend keeps the Basic authorisation header in memory for the active page session; it is not written to local storage.

On the first deployment of this release, add these environment variables to the Render API service **before deploying**:

- `BOOTSTRAP_ADMIN_USERNAME` — initial central administrator username.
- `BOOTSTRAP_ADMIN_PASSWORD` — initial password (minimum 12 characters; use a unique generated password).
- `BOOTSTRAP_ADMIN_DISPLAY_NAME` — optional display name.

Flyway V3 creates the administrator, role, permission, geographical-scope assignment and append-only administrative audit tables. The application creates the initial `CENTRAL_ADMIN` account only when the administrator table is empty. Once the first administrator has been created, remove the bootstrap password variable from Render. Do not put credentials in GitHub or the frontend.

Sign in through the Vercel application using the bootstrap credentials. Central Management can then create administrator accounts, configure custom roles, assign roles at global, organisation or geographical-unit scope, and review administrator audit events.

Role permissions and geographical scope are checked by backend APIs. Unit scope includes the selected geographical unit and its descendants. The legacy branch-based member register is currently restricted to organisation-scoped administrators because branch records are not yet mapped to Cells. Local household/member workflows are the next implementation stage.

The V3 migration is additive. Do not rewrite or rename the already-released V1 or V2 Flyway migrations.
