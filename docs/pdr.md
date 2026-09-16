# Product Design Requirements — MediCore Hospital

## Goal
A modular hospital-management training system that demonstrates realistic hospital workflows without pretending to be production-certified medical software.

## Architecture
- Backend: Java 21 + Spring Boot 3, modular monolith.
- API: REST/JSON.
- Persistence: H2 for lightweight local development; PostgreSQL profile for later production-like use.
- Frontend: React + Vite.
- Security: JWT bearer token + role model.
- Audit: application-level audit events for mutating operations.
- Docker: intentionally postponed.

## Important boundary
This repository is an educational implementation. Real clinical deployment requires validated workflows, regulatory review, threat modelling, migration discipline, backups, observability, integration standards, and substantially deeper testing.

## Phase 4 addendum — production-like resilience (Training/Portfolio, synthetic only)

Additive to (never replacing) the requirements above. Phase 4 demonstrates, on synthetic data only, the engineering practices the boundary section names — migration discipline, backups, observability, containerized review — as **local training/portfolio evidence**. It grants no production, clinical, compliance, or SLA status.

- **Persistence authority.** The PostgreSQL review profile is Flyway-managed with Hibernate `validate`; startup `ddl-auto=update` may not mutate it. Schema evolution is forward-only versioned migrations (V1–V4) with restart idempotency; destructive operations target only `medicore_phase4_`-prefixed disposable databases and fail closed otherwise.
- **Uniqueness scope decision (FR-014).** MRN and invoice-number uniqueness remain **global** (schema-level unique constraints), an explicit documented contract: branch scope is an ownership boundary, not a uniqueness scope. Changing this would require safe migration evidence in a later owner-approved decision.
- **Typed workflow values.** Demonstrated-workflow timestamps and simulated invoice amounts moved from legacy String columns to typed PostgreSQL columns (V3) with wire-format compatibility preserved for existing clients.
- **Time semantics.** Branches carry validated IANA time zones; scheduling/dashboard semantics use the acting branch zone. DST handling is documented behavior of the branch-zone conversion, not a clinical or scheduling guarantee beyond the demonstrated workflows.
- **Verification entry point.** One fail-fast local command, `scripts/phase4/acceptance.sh`, composes migration, real-PostgreSQL workflow/concurrency, backup/restore, Compose health, dependency-loss/recovery, restart, security, OpenAPI drift, frontend, browser-journey, and documentation/traceability gates. It is **local-only**: no `.github/` CI policy exists in this repository, and none is claimed.
- **Boundary unchanged.** Everything in “Important boundary” still applies: no real patient data, no clinical use, no regulatory certification, no production capacity, payment processing, or SaaS tenancy. The Phase 4 stop gate authorizes review of a synthetic Training/Portfolio demonstration only.
