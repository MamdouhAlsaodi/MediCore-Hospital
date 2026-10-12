# MediCore Hospital

> **Educational, synthetic, and non-clinical only.** MediCore is a training/portfolio reference implementation, not certified medical software. Never use it for patient care, clinical decisions, real personal or health data, or production operations. No clinical safety, compliance, capacity, or production-readiness claim is made.

**Status:** The Training/Portfolio scope is complete through Phase 5 and merged into `main`. This is a documentation and local-review milestone, not a deployable release.

MediCore demonstrates operations across **one synthetic hospital network**: a single network organization contains multiple hospitals, branches, and departments. It is not SaaS multi-tenancy or a federation of customer organizations.

## What the project demonstrates

- Server-derived network, hospital, branch, and department acting contexts, with scoped hierarchy views and authorization.
- One synthetic patient identity with explicit hospital-access grants; visibility is not automatically shared across the network.
- Inter-hospital transfer lifecycle with destination-bed reservation, idempotent commands, admission completion, and audit evidence.
- Scoped operational capacity/dashboard summaries and audit views.
- Earlier demonstrated workflows: patient registration/search (now constrained by hospital-access grants), branch-scoped appointments, bed-aware admissions/discharge, emergency-visit states with a neutral demo label, and display-only simulated invoices. None is clinical advice, a real triage protocol, or payment processing.

## Architecture and stack

A React 19 + Vite browser client calls a Java 21 / Spring Boot 3 modular-monolith REST API. Spring Security validates JWT acting contexts against current server-side assignments and hierarchy; services and repositories enforce scope. Spring Data JPA persists to H2 for lightweight local development or Flyway-managed PostgreSQL for disposable review and test runs. Phase 5 uses PostgreSQL migrations V5–V7 for hospital hierarchy, patient access, transfers, reservations, and idempotency. OpenAPI and its generated TypeScript client are drift-checked. This is a single application and database, not a distributed workflow system.

## Safe local quickstart

Start with the [local review runbook](docs/runbook.md), which documents prerequisites, environment-only credential setup, loopback ports, optional synthetic seeding, and cleanup boundaries. Use only disposable local values and a disposable local database; keep credentials out of tracked files. Demo seeding is opt-in and must not target a shared database.

In separate terminals, after setting the required runtime values as described in the runbook:

```bash
cd backend
mvn spring-boot:run
```

```bash
cd frontend
npm install
npm run dev
```

The runbook is authoritative for binding and review configuration; do not expose these development services publicly.

## Acceptance and verification

The **local canonical acceptance scripts** exercise disposable infrastructure and require the prerequisites below. A missing capability is reported as **BLOCKED** (exit 2), not as a pass. These are local review gates, not deployment instructions.

### Phase 4 regression baseline

```bash
bash scripts/phase4/acceptance.sh
bash scripts/phase4/acceptance.sh --list-stages
bash scripts/phase4/acceptance.sh --check-capability
```

This is the 13-stage baseline. It requires a reachable Docker daemon with Compose, PostgreSQL client tooling (`psql`, `pg_dump`, `pg_restore`, `pg_isready`), Node.js/npm/npx, curl and git, and Java 21 with Maven 3.9+ (or the supported `MVN` toolchain override). Details and evidence: [Phase 4 runbook](docs/runbook.md) and [Phase 4 verification](docs/evidence/phase4-verification.md).

### Phase 5 canonical acceptance

```bash
bash scripts/phase5/acceptance.sh
bash scripts/phase5/acceptance.sh --list-stages
bash scripts/phase5/acceptance.sh --check-capability
```

This is the 15-checkpoint gate, including the Phase 4 baseline, PostgreSQL migration and concurrency checks, backend/frontend checks, OpenAPI drift, disposable Compose/browser recovery journeys, documentation, and cleanup. It requires Docker with Compose and a reachable daemon; PostgreSQL tools `psql`, `pg_dump`, `pg_restore`, and `pg_isready`; Node.js/npm; curl and git; plus JDK 21 (including `java` on `PATH`) and Maven, or the supported `MVN` override. Use a disposable local Docker environment only. See [Phase 5 runbook](docs/runbook.md), [Phase 5 specification](specs/005-enterprise-hospital-network-operations/spec.md), and [Phase 5 verification evidence](docs/evidence/phase5-verification.md).

At the recorded canonical run, Phase 4 passed **13/13** stages and Phase 5 passed **15/15** checkpoints. The recorded Phase 5 suites include backend **392/392**, frontend **343/343**, and browser **7 passed / 3 intentionally skipped**; OpenAPI drift and the public-artifact scan were clean. These are local synthetic acceptance results, not a claim of clinical or production suitability.

### Repository CI scope

[`.github/workflows/phase5-postgres.yml`](.github/workflows/phase5-postgres.yml) is a path-filtered pull-request workflow (also manually dispatchable). It runs a **selected PostgreSQL-backed integration suite** and a served OpenAPI/generated-client drift check. It does **not** run either complete local acceptance gate or the full backend/frontend/browser suites; its scope must not be read as broader CI coverage.

## Project documentation

- [Product and architecture requirements](docs/pdr.md)
- [Implementation status and closure evidence](docs/implementation-status.md)
- [Local review runbook](docs/runbook.md)
- [Phase 5 architecture](docs/architecture/phase5-enterprise-network.md)
- [Phase 5 feature specification](specs/005-enterprise-hospital-network-operations/spec.md)
- [Phase 5 verification evidence](docs/evidence/phase5-verification.md)
- [Phase 4 verification evidence](docs/evidence/phase4-verification.md)
- [Traceability map](docs/traceability.md)
- [API reference](docs/api.md)
- [Security threat model](docs/security/threat-model-phase5.md)
- [License](LICENSE) · [Contributing](CONTRIBUTING.md) · [Security reporting instructions](SECURITY.md)

## Vulnerability reporting

Please report suspected vulnerabilities **privately**, not in a public issue: use [GitHub's private vulnerability reporting form](https://github.com/MamdouhAlsaodi/MediCore-Hospital/security/advisories/new) or follow [SECURITY.md](SECURITY.md).

## Boundary and license

All records and journeys are synthetic. This project does not authorize real-data use, clinical use, deployment, or production operation. The simulated workflows and locally tested safeguards are educational evidence only. See [implementation status](docs/implementation-status.md) for explicit limits and the closure record. Distributed transactions, independent customer tenancy, external clinical/payment integrations, and production guarantees are outside this project scope.

MediCore is released under the [MIT License](LICENSE).
