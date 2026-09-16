# MediCore Hospital

> **Educational, non-clinical, uncertified.** MediCore is a training implementation of a modular hospital-management system. It is **not** certified medical software, holds no regulatory approval, and must never be used for patient care, real clinical decisions, or real personal data. Phase 3 demonstrates **one synthetic hospital organization with three synthetic branches** — a Training/Portfolio scope boundary, not SaaS multi-tenancy, a Pilot, or production readiness.

## What this project demonstrates

One complete, test-pinned, **branch-scoped** care workflow on synthetic data only:

1. **Login and acting context** — JWT bearer authentication whose authority comes from an enabled acting assignment (role + scope), re-derived from server state on every request. The acting-context selector offers only server-issued (assignment, branch) pairs and switching issues a replacement context-bound token; foreign or disabled targets fail closed.
2. **Register a patient** — validated create (`ADMIN`/`RECEPTIONIST`) inside the acting branch; ownership is server-stamped, never client-chosen.
3. **Search and inspect** — case-insensitive name search scoped to the branch; a cross-branch MRN is indistinguishable from a missing record; read-only detail view with data minimization.
4. **Edit** — update the four permitted contact fields; identity fields stay immutable through the API.
5. **Schedule an appointment** — against same-branch references, inside one of the professional's modeled availability intervals, with explicit duration; overlapping or outside-availability requests are refused with `409` and nothing is written.
6. **Admission with bed lifecycle** — admit into an available bed, transfer atomically (source released and target occupied in one transaction), and discharge with server-stamped `dischargedAt` and bed release in the same transaction; duplicate bed identity, invalid transitions, and unavailable targets return `409`.
7. **Emergency visit** — a neutral `1–5` demo triage label (no clinical meaning, not a real triage protocol) and guarded `WAITING → IN_TREATMENT → CLOSED` transitions, branch-scoped.
8. **Invoice (financial simulation)** — ADMIN/BILLING only: uniquely numbered, display-only demo invoices through `DRAFT → ISSUED → PAID` or `VOID` — no payments, tax, FX, or collection.
9. **Branch and network command centers** — a typed summary of the acting branch only, plus an organization comparison (totals exactly equal to the per-branch sums) served only to `ORGANIZATION`-scope ADMIN.
10. **Audit evidence** — exactly one audit event per successful mutation carrying actor, acting assignment, role, branch, and correlation id (never tokens, passwords, or request bodies); failures record nothing; ADMIN views are scope-aware.

Role-based access is enforced in the backend (`401` for no valid session/context, `403` for a refused role or scope), independent of what the interface hides. Failure states — validation, malformed references, cross-branch access, illegal transitions, duplicates, scheduling conflicts, permission denials, session expiry — are handled and tested at every step. The full walk-throughs with real request/response shapes: [`docs/patient-journey.md`](docs/patient-journey.md), [`docs/care-operations.md`](docs/care-operations.md), and [`docs/multi-branch-operations.md`](docs/multi-branch-operations.md).

## Stack

- **Backend:** Java 21, Spring Boot 3 (modular monolith), Spring Security with JWT acting contexts, Spring Data JPA, H2 for lightweight local development plus a Flyway-managed PostgreSQL review profile (Testcontainers-verified), Actuator liveness/readiness, structured JSON logs, Micrometer/Prometheus metrics.
- **Frontend:** React 19 + Vite, no router or UI-framework dependencies; typed generated OpenAPI client with feature-scoped transport adapters; acting-context selector and branch-aware screens.
- **Tooling:** JUnit integration suites (backend, incl. real PostgreSQL concurrency/migration tests), Vitest + Testing Library (frontend), pinned Playwright browser journeys at desktop and mobile viewports, Docker Compose review stack, guarded backup/restore rehearsals, and a repeatable 36-step API smoke with per-step timings.

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

Optional synthetic demo cohort (one organization, three branches, branch-owned departments/patients/professionals/beds/admissions/emergency visits/invoices with dated availability; idempotent; off by default): start the backend with `MEDICORE_DEMO_SEED=true`. Never enable it against a shared database. The bootstrap `admin` account receives an `ORGANIZATION`-scope `ADMIN` assignment, so it can switch the acting context across all three branches.

Automated verification:

```bash
cd backend && mvn test                        # full backend suite (unit + PostgreSQL integration)
cd ../frontend && npm test && npm run build   # frontend suite + production build
cd ../frontend && npm run test:e2e            # real-browser journeys (see docs/runbook.md)
```

### Phase 4 canonical acceptance (one command, fail-fast)

Phase 4 adds a production-like resilience slice: Flyway/PostgreSQL migrations,
Docker/Compose review stack, guarded backup/restore, observability
(liveness/readiness split, structured logs, bounded metrics), hardened HTTP
security, deterministic OpenAPI with a generated typed client, and a full
containerized Playwright journey with restart and dependency-loss recovery.
Everything is verified by one canonical, fail-fast, local command:

```bash
scripts/phase4/acceptance.sh          # 13 serial stages; exit 0 only when all pass
scripts/phase4/acceptance.sh --list-stages
```

Requires Docker with a reachable daemon, PostgreSQL client tooling (`psql`),
Node/npm, and a JDK 21 / Maven 3.9+ backend toolchain — resolved automatically
(host `mvn`, an `MVN` override, or the script's built-in containerized Maven;
see the comments in `scripts/phase4/acceptance.sh`).
A missing capability exits 2 (BLOCKED) — never a silent pass — and stage 0 of
every run proves that negatively. All disposable containers, databases, and
volumes are process-owned, named with the `medicore_phase4` convention, and
removed on exit; the run never touches unrelated host services. There is no
repository CI policy (no `.github/` directory exists), so this gate is
documented as **local-only** — no CI approval is claimed. Evidence map for
every requirement: [`docs/evidence/phase4-verification.md`](docs/evidence/phase4-verification.md).
Phase 4 guard scripts live in `scripts/phase4/` (target guard, artifact scan,
OpenAPI drift, container static checks, backup/restore, security headers,
live observability rehearsal, container E2E orchestrator).

Repeatable branch-aware journey smoke against a running, disposable local backend (seed enabled):

```bash
BASE_URL=http://127.0.0.1:5501 RUNS=1 \
HOSPITAL_SMOKE_PASSWORD="<same disposable local value>" \
./scripts/smoke-patient-journey.sh
```

## Documentation

| Document | Contents |
|---|---|
| [`docs/multi-branch-operations.md`](docs/multi-branch-operations.md) | The Phase 3 portfolio story: what the multi-branch slice demonstrates, its evidence, and its deliberate limits |
| [`docs/patient-journey.md`](docs/patient-journey.md) | The demonstrated patient journey end-to-end: acting context, steps, roles, API shapes, failure states, audit evidence |
| [`docs/care-operations.md`](docs/care-operations.md) | The demonstrated care-operations journey: bed-aware admission/discharge, emergency visit, simulated invoice, command centers, audit |
| [`docs/architecture/multi-branch-operations.md`](docs/architecture/multi-branch-operations.md) | Phase 3 architecture: UI → adapters → controllers → scoped services → branch-aware repositories, acting-context and audit as cross-cutting adapters |
| [`docs/architecture/patient-journey.md`](docs/architecture/patient-journey.md) | Patient-journey architecture diagram and layer boundaries, RBAC layers, error contract |
| [`docs/architecture/care-operations.md`](docs/architecture/care-operations.md) | Care-operations layering: DTO allowlists, server-owned lifecycles, RBAC authority, exactly-once audit |
| [`docs/traceability.md`](docs/traceability.md) | Every requirement mapped to tasks, commits, tests, and runtime evidence — including the 14-row Phase 3 acceptance map |
| [`docs/api.md`](docs/api.md) | Endpoint reference, acting-context contract, enforced role matrix, error contract, demo-data flag |
| [`docs/runbook.md`](docs/runbook.md) | Local review setup, three-branch demo seeding, review accounts, test gates, browser-evidence runner, smoke/performance commands |
| [`docs/implementation-status.md`](docs/implementation-status.md) | Truthful task status across all phases, known gaps, and explicitly not-done items |
| [`docs/evidence/phase4-verification.md`](docs/evidence/phase4-verification.md) | Phase 4 acceptance evidence: FR-001..020 and SC-001..010 mapped to exact source, tests, and runtime stages |
| [`docs/performance.md`](docs/performance.md) | Dated local smoke baselines and the regression budget (no capacity claims) |
| [`docs/plan1.md`](docs/plan1.md) / [`docs/plan2.md`](docs/plan2.md) / [`docs/plan3.md`](docs/plan3.md) | The executed roadmaps, each with its stop gate |
| [`docs/pdr.md`](docs/pdr.md) | Product and architecture boundaries |

## Scope boundary (read this)

This repository is intentionally **not** production-ready: no Pilot preparation, no regulatory review, no integrations, no SaaS multi-tenancy, and no clinical certification. It is training/portfolio software over synthetic data only — no clinical decision support, no medical advice, no real triage protocol, no payment processing, no real hospital data, and no capacity, SLA, or security-certification claim. Phase 4's Docker Compose stack, PostgreSQL migrations, backup/restore rehearsal, and observability are **local training/portfolio engineering evidence on synthetic data** — they demonstrate realistic engineering practice, not a deployment authorization. The roadmap is complete through the Phase 4 stop gate: a synthetic Training/Portfolio, single-organization, multi-branch demonstration — no Pilot, later phase, or real-data work may start without the owner's explicit, separate decision. Details: [`docs/implementation-status.md`](docs/implementation-status.md) and [`docs/traceability.md`](docs/traceability.md).
