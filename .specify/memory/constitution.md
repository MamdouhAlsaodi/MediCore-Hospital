# MediCore Constitution

## Core Principles

### I. Truthful Training/Portfolio Boundary
MediCore is an educational, non-clinical system using synthetic data only. Work MUST NOT introduce real patient data, clinical-use claims, regulatory certification claims, production-capacity claims, payment processing, or unsupported SLA/SLO language. Production-like engineering means realistic engineering evidence, not production or clinical authorization.

### II. Server-Owned Authority and Isolation
Authentication, acting assignment, organization/branch scope, lifecycle transitions, audit context, ownership, and data visibility remain server-authoritative. Client-provided identifiers never widen authority. Cross-branch records remain hidden through scoped persistence and service checks. Every sensitive mutation remains transactional and auditable.

### III. Test-First, PostgreSQL-Real Verification
Every behavior change starts with a failing focused test. Persistence, migrations, constraints, concurrency, backup/restore, and recovery claims require real disposable PostgreSQL evidence; H2-only proof is insufficient for Phase 4. Existing H2 developer flow remains lightweight and green unless an explicitly documented migration decision replaces it.

### IV. Forward-Only Data Safety
Schema evolution uses versioned Flyway migrations. No startup `ddl-auto=update` may mutate production-like PostgreSQL. Migration rehearsal starts from a known synthetic baseline, validates counts/relationships/invariants, and proves restart idempotency. Destructive operations target only explicitly named disposable databases and fail closed otherwise.

### V. Observable and Secure by Default
Logs are structured and correlation-aware without credentials, tokens, request bodies, or sensitive personal fields. Liveness is process-only; readiness proves required dependencies. Metrics have bounded cardinality. Secrets come only from runtime environment. Public exposure is minimal, rate limits are bounded, and security failures are generic and tested.

### VI. Contract-First Cross-Client Changes
OpenAPI is generated from the backend contract and verified for drift. Frontend transport code consumes generated or generated-derived typed contracts rather than hand-maintained duplicated shapes. Wire compatibility, error bodies, authentication headers, and exact serialization are tested before UI adoption.

### VII. Small, Reversible, Evidence-Bearing Steps
Implement dependency-ordered tasks with narrow responsibility, SOLID design, no speculative modules, and no unrelated raw-CRUD expansion. Every task records changed paths, commands, results, and a `PASS | PARTIAL | BLOCKED` checkpoint. Failure stops the sequence; it is never papered over by weakening tests.

## Phase 4 Constraints

- Baseline: `v0.3.0-phase3-stable` / `1b59a44b4729b9e1ff88d6822f153f4bd56ab3ac`.
- Stack: Java 21, Spring Boot 3.5.5, React 19, Vite 7, PostgreSQL, Flyway, Docker Compose, Testcontainers, Micrometer/Prometheus, OpenAPI.
- One synthetic organization with three synthetic branches remains the domain boundary; this phase does not create SaaS tenancy.
- Preserve existing patient → appointment → admission/bed → discharge, emergency, simulated invoice, command-center, and audit behavior.
- Do not add Pharmacy, Laboratory, Radiology, FHIR, HL7, PACS, insurer, payment, MFA/SSO, or clinical decision support.
- Do not invent retention periods, legal obligations, clinical policies, RPO/RTO promises, or production thresholds.
- No credentials, private addresses, machine-specific absolute paths, or generated secrets in tracked files.

## Development Workflow and Gates

1. Capture baseline status and run existing backend/frontend/build gates.
2. Use RED → GREEN → REFACTOR for each code task.
3. Use only disposable, explicitly named PostgreSQL databases for migration/destructive tests.
4. Keep migration, security, and cross-client contract changes serialized.
5. After each task, update the Phase 4 report with paths, commands, counts, and verdict.
6. Final acceptance requires clean backend tests, frontend tests/build, PostgreSQL integration, migration rehearsal, backup/restore, Compose health, container journey, OpenAPI drift, security matrix, and documentation traceability.
7. Git commit, push, PR, deployment, or release are outside this execution unless separately authorized.

## Governance

This constitution governs Phase 4 artifacts and implementation. The owner's latest explicit instruction supersedes it. Amendments require a documented reason and must preserve the Training/Portfolio and synthetic-only boundary. Complexity requires written justification in the plan. A passing test alone does not authorize publication or deployment.

**Version**: 1.0.0 | **Ratified**: 2026-09-14 | **Last Amended**: 2026-09-14
