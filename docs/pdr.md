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
- **Verification entry point.** One fail-fast local command, `scripts/phase4/acceptance.sh`, composes migration, real-PostgreSQL workflow/concurrency, backup/restore, Compose health, dependency-loss/recovery, restart, security, OpenAPI drift, frontend, browser-journey, and documentation/traceability gates. At the original Phase 4 acceptance snapshot there was no repository CI policy; Phase 5 subsequently added a narrower `.github/workflows/phase5-postgres.yml` pull-request check. That workflow does not replace the local 13-stage or 15-stage acceptance gates.
- **Boundary unchanged.** Everything in “Important boundary” still applies: no real patient data, no clinical use, no regulatory certification, no production capacity, payment processing, or SaaS tenancy. The Phase 4 stop gate authorizes review of a synthetic Training/Portfolio demonstration only.

## Phase 5 addendum — enterprise network operations (synthetic Training/Portfolio)

This addendum records the implemented Phase 5 design and current code boundaries. The local Phase 5 acceptance gate passed within the synthetic Training/Portfolio scope; it does not widen the product scope to clinical or production use. All records and journeys remain synthetic, educational, and non-clinical.

- **Hierarchy.** Retain `HospitalOrganization` as the single network boundary; add `HospitalFacility` beneath it and require each branch to belong to one hospital in the same organization. Departments continue to inherit facility through their branch. `ORGANIZATION` remains the network assignment scope; `HOSPITAL`, `BRANCH`, and `DEPARTMENT` scopes add bounded authority below it. This is one synthetic network, not customer tenancy.
- **Legacy migration.** Forward-only Flyway migrations V5–V7 extend the Phase 4 schema. V5 deterministically maps existing branches to `LEGACY-HOSPITAL-001` and refuses malformed ownership or duplicate hospital-local branch codes; V6 derives network patient ownership and one legacy hospital access grant per existing patient; V7 adds transfer, bed-reservation, and idempotency records. No migration guesses unresolved ownership.
- **Authority and identity.** The request context binds one role, assignment, organization, hospital, and concrete branch (with an optional department). JWT structural claims are checked against reloaded server state; client-provided identifiers are references, not authority. A patient is one network identity, but hospital operations require an active `PatientHospitalAccess` grant.
- **Transfer boundary.** Transfer is an in-process modular-monolith workflow, not a distributed saga: `REQUESTED → ACCEPTED → IN_TRANSIT → COMPLETED`, with `REJECTED` and `CANCELLED` alternatives. Acceptance, destination reservation/access, completion, and audit effects are transactionally coordinated. Database uniqueness/locking and optimistic versioning guard conflicting operations; transfer commands require bounded idempotency keys. These safeguards are verified against the tested database paths and do not claim distributed or production concurrency guarantees.
- **Operational views.** Hierarchy and dashboard summaries are server-scoped and operational only. They must not expose clinical recommendations, diagnosis, risk scoring, or real triage. Phase 4's global MRN/invoice uniqueness and synthetic-only boundary remain in force unless separately changed and verified.
- **Acceptance boundary.** The detailed component/data-flow view is [`docs/architecture/phase5-enterprise-network.md`](architecture/phase5-enterprise-network.md); threat analysis is [`docs/security/threat-model-phase5.md`](security/threat-model-phase5.md). Local review and migration commands, plus their limits, are documented in [`docs/runbook.md`](runbook.md). The fresh `scripts/phase5/acceptance.sh` run passed all 15 checkpoints, including the Phase 4 regression baseline, PostgreSQL rehearsal, browser journeys, traceability, and cleanup; see [`docs/evidence/phase5-verification.md`](evidence/phase5-verification.md). This is local synthetic acceptance only, not authorization for pilot, real data, clinical use, production deployment, or release.
