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

- Create organisations.
- Create branches within an organisation, including district and country metadata.
- Configure member classifications per organisation.
- Register members against a branch.
- Store an optional ZCC app number, unique when supplied.
- Search a branch register by member name or ZCC app number.

The membership application integration is not connected. Use fictional demonstration records only.

## API overview

- `GET/POST /api/organisations`
- `GET/POST /api/organisations/{organisationId}/branches`
- `GET/POST /api/organisations/{organisationId}/classifications`
- `GET/POST /api/branches/{branchId}/members`

## Current limitations

Authentication and role-based access control are not implemented yet. Do not deploy this development build with real member data or expose it to the public internet. Contribution posting, receipts, deposits and reconciliation are planned for subsequent milestones.

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
- The current release is a development foundation. Authentication and role-based access control are not implemented; do not enter real member or financial data or expose this system for operational use.


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
