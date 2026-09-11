# MediCore Hospital

> **Educational, non-clinical, uncertified.** MediCore is a training implementation of a modular hospital-management system. It is **not** certified medical software, holds no regulatory approval, and must never be used for patient care, real clinical decisions, or real personal data.

## What this project demonstrates

One complete, test-pinned care workflow on synthetic data only:

1. **Login** — JWT bearer authentication with server-enforced roles.
2. **Register a patient** — validated create (`ADMIN`/`RECEPTIONIST`).
3. **Search and inspect** — case-insensitive name search, read-only detail view with data minimization.
4. **Edit** — update the four permitted contact fields; identity fields stay immutable through the API.
5. **Schedule an appointment** — against verified patient and professional references with a strict status contract.
6. **Admission and discharge** — a verified-reference admission; the server sets `ADMITTED` and stamps `dischargedAt` at the single legal transition.
7. **Emergency visit** — a neutral `1–5` demo triage label (no clinical meaning, not a real triage protocol) and guarded `WAITING → IN_TREATMENT → CLOSED` transitions.
8. **Invoice (financial simulation)** — ADMIN/BILLING only: uniquely numbered, display-only demo invoices through `DRAFT → ISSUED → PAID` or `VOID` — no payments, tax, FX, or collection.
9. **Status-aware dashboard** — eleven count keys covering the lifecycles, with labeled sections.
10. **Audit evidence** — exactly one audit event per successful mutation, ADMIN-visible; failures record nothing.

Role-based access is enforced in the backend (`401` for no session, `403` for a refused role), independent of what the interface hides. Failure states — validation, malformed references, illegal transitions, duplicates, permission denials, session expiry — are handled and tested at every step. The full walk-throughs with real request/response shapes: [`docs/patient-journey.md`](docs/patient-journey.md) and [`docs/care-operations.md`](docs/care-operations.md).

## Stack

- **Backend:** Java 21, Spring Boot 3 (modular monolith), Spring Security with JWT, Spring Data JPA, H2 for local development (PostgreSQL profile deferred to a later phase), Actuator health endpoint.
- **Frontend:** React + Vite, no router or UI-framework dependencies; feature-scoped transport adapters over one shared `apiFetch`.
- **Tooling:** JUnit integration suites (70 backend tests), Vitest + Testing Library (125 frontend tests), a repeatable API smoke script with per-step timings.

## Run and verify locally (loopback only)

```bash
# backend on http://127.0.0.1:5501 — credentials come from the environment,
# never from the repository (see docs/runbook.md for the requirements)
cd backend
HOSPITAL_ADMIN_PASSWORD="<disposable local value, >=12 chars>" \
HOSPITAL_JWT_SECRET="<disposable local value>" \
mvn spring-boot:run

# frontend on http://127.0.0.1:5502 (proxies /api to the backend)
cd frontend
npm install
npm run dev
```

Optional synthetic demo cohort (3 patients, 2 professionals, 2 appointments, 2 admissions, 3 emergency visits, 4 simulated invoices; idempotent; off by default): start the backend with `MEDICORE_DEMO_SEED=true`. Never enable it against a shared database.

Automated verification:

```bash
cd backend && mvn test                        # 70 tests
cd ../frontend && npm test && npm run build   # 125 tests + production build
```

Repeatable journey smoke against a running, disposable local backend:

```bash
BASE_URL=http://127.0.0.1:5501 RUNS=3 \
HOSPITAL_SMOKE_PASSWORD="<same disposable local value>" \
./scripts/smoke-patient-journey.sh
```

## Documentation

| Document | Contents |
|---|---|
| [`docs/patient-journey.md`](docs/patient-journey.md) | The demonstrated patient journey end-to-end: steps, roles, API shapes, failure states, audit evidence |
| [`docs/care-operations.md`](docs/care-operations.md) | The demonstrated care-operations journey: admission/discharge, emergency visit, simulated invoice, dashboard, audit |
| [`docs/architecture/patient-journey.md`](docs/architecture/patient-journey.md) | Patient-journey architecture diagram and layer boundaries (UI → adapters → controllers → services → repositories/audit), RBAC layers, error contract |
| [`docs/architecture/care-operations.md`](docs/architecture/care-operations.md) | Care-operations layering: DTO allowlists, server-owned lifecycles, RBAC authority, exactly-once audit, dashboard aggregation |
| [`docs/traceability.md`](docs/traceability.md) | Every requirement mapped to tasks, commits, tests, and runtime evidence |
| [`docs/api.md`](docs/api.md) | Endpoint reference, enforced role matrix, error contract, demo-data flag |
| [`docs/runbook.md`](docs/runbook.md) | Local review setup, demo seeding, test gates, smoke/performance commands |
| [`docs/implementation-status.md`](docs/implementation-status.md) | Truthful task status, known gaps, and explicitly not-done items |
| [`docs/performance.md`](docs/performance.md) | Dated local smoke baselines and the regression budget (no capacity claims) |
| [`docs/plan1.md`](docs/plan1.md) | The roadmap this milestone executed, with its stop gate |
| [`docs/pdr.md`](docs/pdr.md) | Product and architecture boundaries |

## Scope boundary (read this)

This repository is intentionally **not** production-ready: no Docker, no Pilot preparation, no browser-verified screenshots, no regulatory review, no backups/observability/integrations, and no clinical certification. It is training/portfolio software over synthetic data only — no clinical decision support, no medical advice, no real triage protocol, no payment processing, no real hospital data. Docker is postponed by design, and the next step after this milestone is a formal Training/Portfolio acceptance review — not deployment; no further phase is authorized. Details: [`docs/implementation-status.md`](docs/implementation-status.md).
