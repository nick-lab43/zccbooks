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
