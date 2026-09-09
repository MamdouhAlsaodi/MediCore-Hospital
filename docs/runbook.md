# Local Review Runbook

MediCore is an educational, non-clinical training project, not certified clinical software. Do not use it for patient care or real clinical decisions.

## Runtime values
Provide `HOSPITAL_ADMIN_PASSWORD` and `HOSPITAL_JWT_SECRET` only through the runtime environment; do not place secrets in tracked files. `HOSPITAL_ADMIN_PASSWORD` is required (minimum 12 characters) while the `admin` account does not exist yet; startup fails with a clear configuration error if it is missing, blank, or too short, and an existing `admin` account is never modified. Optional PostgreSQL values are `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

## Authentication and authorization
The login response returns `accessToken`, `tokenType`, `username`, and the account's role names; it never returns a password hash or internal entity. The frontend stores the session in `sessionStorage` only (cleared when the tab closes) and purges the legacy `localStorage.token` key on boot. Unauthenticated visitors see only the dedicated Login page. Endpoint families carry explicit role rules in `SecurityConfig` ordered before an ADMIN-only catch-all for unmatched `/api/**`; ADMIN has full access. Unauthenticated requests receive `401`; an authenticated role without permission receives `403`.

## Backend
Requirements: Java 21 and Maven 3.9+.

```bash
cd backend
HOSPITAL_ADMIN_PASSWORD="$HOSPITAL_ADMIN_PASSWORD" HOSPITAL_JWT_SECRET="$HOSPITAL_JWT_SECRET" mvn spring-boot:run
```

The review backend listens on loopback port `5501`. H2 file data is local-only under `backend/data/`. The H2 console is disabled and must never be exposed or re-enabled for public review. Authorization integration tests (`SecurityAuthorizationTest`) run against an isolated in-memory H2 database and never touch the production H2 files.

## Frontend

Development (live reload):

```bash
cd frontend
REVIEW_BIND_HOST=<operator-supplied-tailnet-interface-address> npm run dev
```

Serving the already-built bundle for review:

```bash
cd frontend
npm run build
REVIEW_BIND_HOST=<operator-supplied-tailnet-interface-address> npm run preview
```

Both the Vite dev server and the preview server bind only to the narrow Tailnet interface address supplied through `REVIEW_BIND_HOST`, listen on port `5502` with `strictPort` enabled, and proxy `/api` to the loopback backend on port `5501`. Do not replace this with a broad host bind.

## Synthetic demo data (opt-in, disabled by default)

Demo seeding is off unless explicitly enabled. To seed a small synthetic demo cohort into a local review backend, set `MEDICORE_DEMO_SEED=true` in the runtime environment before starting it:

```bash
cd backend
MEDICORE_DEMO_SEED=true HOSPITAL_ADMIN_PASSWORD="$HOSPITAL_ADMIN_PASSWORD" HOSPITAL_JWT_SECRET="$HOSPITAL_JWT_SECRET" mvn spring-boot:run
```

What gets created (all values are obviously synthetic; no real personal or clinical data):

- 3 patients: `Demo Patient Alpha` (`DEMO-0001`), `Demo Patient Bravo` (`DEMO-0002`), `Demo Patient Charlie` (`DEMO-0003`)
- 2 professionals: `Demo Physician Alpha` (`DEMO-STAFF-001`, internal medicine), `Demo Nurse Bravo` (`DEMO-STAFF-002`, nursing)
- 2 appointments linking them: Alpha ↔ Physician (consultation, scheduled) and Bravo ↔ Nurse (follow-up, confirmed)

Seeding is idempotent and safe to restart: every insert is lookup-before-create (patient MRN, professional employee code, appointment key), so restarting never duplicates rows, and it never deletes or modifies existing records. Seeded rows are written directly through the repositories, so they do not produce audit events, and no accounts or credentials are created.

Verify by logging in as an ADMIN and checking Patients (search `DEMO-`) and Appointments. Never enable demo seeding against a shared or production data store.

## PostgreSQL later
Use the `postgres` Spring profile only with runtime-provided `DB_URL`, `DB_USER`, and `DB_PASSWORD`. Docker and public deployment are outside this phase.
