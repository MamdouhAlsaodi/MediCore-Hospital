# Phase 4 Quickstart — Synthetic Review Only

> This is an educational, non-clinical review environment. Use synthetic data only.

## Local lightweight path

```bash
cd backend
mvn test
cd ../frontend
npm ci
npm test
npm run build
```

## PostgreSQL integration path

Use the repository's Phase 4 guarded Testcontainers profile. The canonical command will be added by the implementation and must fail if Docker is unavailable rather than silently falling back to H2.

## Compose review path

1. Copy `.env.example` to an untracked local file.
2. Supply required process-local secrets; never commit them.
3. Run the documented `docker compose up --build` command.
4. Wait for PostgreSQL health, backend readiness, and frontend health.
5. Run the synthetic smoke/acceptance command.
6. Tear down normally; remove volumes only when explicitly resetting the disposable review database.

## Backup/restore rehearsal

Run the Phase 4 backup script against the named synthetic review database, validate the archive/checksum, restore only to the script's accepted disposable target pattern, then run the invariant verifier. Do not print connection URLs or row contents.

## Canonical acceptance

The final implementation must provide one script/command that composes the backend, frontend, PostgreSQL, migration, backup/restore, security, OpenAPI, browser, restart/recovery, and public-artifact checks and exits non-zero on any failed gate.
