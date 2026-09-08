# Local Review Runbook

MediCore is an educational, non-clinical training project, not certified clinical software. Do not use it for patient care or real clinical decisions.

## Runtime values
Provide `HOSPITAL_ADMIN_PASSWORD` and `HOSPITAL_JWT_SECRET` only through the runtime environment; do not place secrets in tracked files. `HOSPITAL_ADMIN_PASSWORD` is required (minimum 12 characters) while the `admin` account does not exist yet; startup fails with a clear configuration error if it is missing, blank, or too short, and an existing `admin` account is never modified. Optional PostgreSQL values are `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

## Backend
Requirements: Java 21 and Maven 3.9+.

```bash
cd backend
HOSPITAL_ADMIN_PASSWORD="$HOSPITAL_ADMIN_PASSWORD" HOSPITAL_JWT_SECRET="$HOSPITAL_JWT_SECRET" mvn spring-boot:run
```

The review backend listens on loopback port `5501`. H2 file data is local-only under `backend/data/`. The H2 console is disabled and must never be exposed or re-enabled for public review.

## Frontend

```bash
cd frontend
REVIEW_BIND_HOST=<operator-supplied-tailnet-interface-address> npm run dev
```

The Vite review server binds only to the narrow Tailnet interface address supplied through `REVIEW_BIND_HOST`, listens on port `5502` with `strictPort` enabled, and proxies `/api` to the loopback backend on port `5501`. Do not replace this with a broad host bind.

## PostgreSQL later
Use the `postgres` Spring profile only with runtime-provided `DB_URL`, `DB_USER`, and `DB_PASSWORD`. Docker and public deployment are outside this phase.
