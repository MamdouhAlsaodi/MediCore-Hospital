# Feature Specification: Phase 4 — Production-Like Engineering & Operational Resilience

**Feature Branch**: `phase4/production-like-resilience`

**Created**: 2026-09-14

**Status**: Owner approved for implementation

**Input**: Evolve the accepted Phase 3 synthetic Training/Portfolio system into a production-like engineering demonstration with PostgreSQL migrations, containerized review, backup/restore rehearsal, observability, security hardening, time/data correctness, generated API contracts, and failure testing—without claiming clinical or production readiness.

## User Scenarios & Testing

### User Story 1 — Repeatable PostgreSQL review environment (Priority: P1)

A technical reviewer can start MediCore with PostgreSQL from a clean checkout, observe versioned migrations, load only the opt-in synthetic cohort, restart safely, and run the accepted hospital journey without schema drift.

**Why this priority**: Every later operational claim depends on a real database and deterministic schema lifecycle.

**Independent Test**: Start a disposable PostgreSQL database, apply Flyway from empty, boot twice, run the existing workflow tests/smoke, and verify migration history plus unchanged domain invariants.

**Acceptance Scenarios**:
1. **Given** an empty disposable PostgreSQL database, **When** MediCore starts under the review profile, **Then** Flyway applies all migrations and Hibernate validates without altering schema.
2. **Given** the migrated database, **When** the service restarts, **Then** no migration is reapplied and the synthetic rows remain coherent and unduplicated.
3. **Given** concurrent bed and appointment commands, **When** they race on PostgreSQL, **Then** exactly one legal winner persists and losers receive the existing conflict contract.

---

### User Story 2 — One-command containerized review (Priority: P1)

A reviewer can launch PostgreSQL, backend, and frontend with Docker Compose using runtime environment values, wait for real health/readiness, complete the synthetic journey, and tear down the disposable environment.

**Independent Test**: `docker compose up --build` reaches healthy state, anonymous/protected route behavior is correct, the synthetic smoke passes, and teardown removes the disposable volume when explicitly requested.

**Acceptance Scenarios**:
1. **Given** required non-secret configuration and generated process-local secrets, **When** Compose starts, **Then** PostgreSQL becomes healthy before backend readiness and frontend starts only after its dependency is ready.
2. **Given** an unavailable database, **When** backend is alive, **Then** liveness remains truthful while readiness fails.
3. **Given** a clean teardown, **When** the stack is started again, **Then** migrations and seed behavior are deterministic.

---

### User Story 3 — Verifiable backup and restore (Priority: P1)

An operator can back up the synthetic PostgreSQL review database, restore it into a separately named disposable database, and receive a machine-verifiable integrity report without exposing row contents or credentials.

**Independent Test**: Create a backup, validate its archive, restore to a fresh target, and compare allowlisted counts, foreign-key relationships, lifecycle invariants, and audit coverage.

**Acceptance Scenarios**:
1. **Given** a healthy synthetic review database, **When** backup runs, **Then** it emits a non-empty archive and checksum without printing credentials.
2. **Given** the archive, **When** restore targets a non-disposable or source database, **Then** the script refuses before mutation.
3. **Given** a fresh disposable restore target, **When** restore and verification finish, **Then** counts and invariants match the source and the report records measured duration as training evidence, not an SLA.

---

### User Story 4 — Actionable observability without sensitive leakage (Priority: P2)

An operator can correlate a request across structured logs, inspect bounded application/HTTP/database metrics, and distinguish liveness from readiness without tokens, passwords, request bodies, or patient fields entering telemetry.

**Independent Test**: Send successful and failing requests with valid/invalid correlation IDs, inspect sanitized JSON logs and Prometheus output, and prove readiness changes when PostgreSQL is unavailable.

---

### User Story 5 — Hardened authentication boundary (Priority: P2)

A security reviewer can demonstrate bounded login rate limiting, explicit CORS/security-header policy, generic authentication failures, immediate account/assignment invalidation, and no authority from forged headers or JWT role strings.

**Independent Test**: Run an HTTP matrix for anonymous, valid role, denied role, tampered token, disabled account/assignment, invalid Origin, repeated login failure, and recovery after the documented rate-limit window.

---

### User Story 6 — Correct branch-local time and scoped identifiers (Priority: P2)

A user sees and schedules operational times according to an explicit branch IANA time zone while storage remains unambiguous. DST gaps/overlaps are rejected or resolved by documented policy. Department reads and selected human identifiers follow documented scope rules.

**Independent Test**: Exercise two branches in different IANA zones across ordinary and DST-boundary instants and verify the same stored instant renders correctly without changing branch authority.

---

### User Story 7 — Generated, drift-checked API contract (Priority: P2)

A frontend developer can regenerate the OpenAPI document and typed client deterministically, with CI/tests failing if backend routes or DTOs drift.

**Independent Test**: Generate twice for byte-stable output, run drift check, then exercise representative auth, patient, appointment, admission, dashboard, and audit calls through the generated client contract.

---

### User Story 8 — Recovery and failure evidence dossier (Priority: P3)

A reviewer can inspect a public-safe dossier proving migration, restore, restart, dependency failure, concurrency, container journey, security, API drift, and regression gates, followed by a formal Phase 4 stop gate.

**Independent Test**: Run the canonical acceptance script from a clean checkout and map each requirement to exact source, automated test, runtime evidence, and documentation.

## Edge Cases

- Empty database versus an existing Phase 3-shaped synthetic schema.
- Migration interrupted before completion and restart afterward.
- Unknown legacy/null-branch rows: never auto-adopted or exposed.
- PostgreSQL unavailable at startup or lost after startup.
- Two concurrent bed claims; two overlapping appointment creates; duplicate migration startup.
- Backup archive missing/corrupt or restore target not explicitly disposable.
- Invalid IANA zone; nonexistent DST local time; ambiguous DST overlap.
- Metrics/log dimensions containing IDs or attacker-controlled unbounded values.
- Login-rate-limit memory growth and direct-client address parsing without trusting forwarded headers by default.
- OpenAPI generation bootstrapping without a safe isolated database.
- Frontend generated client receiving `401`, `403`, generic `404`, `409`, and stable validation `400`.

## Requirements

### Functional Requirements

- **FR-001**: PostgreSQL review profile MUST use Flyway and Hibernate `validate`; schema mutation through `ddl-auto=update` is forbidden there.
- **FR-002**: Versioned migrations MUST create the accepted schema from empty and support a documented synthetic Phase 3 rehearsal path.
- **FR-003**: Migration verification MUST prove row counts, ownership, required references, uniqueness, lifecycle, and audit invariants without printing sensitive rows.
- **FR-004**: Real PostgreSQL integration tests MUST cover scoped workflows and concurrency.
- **FR-005**: Dockerfiles and Compose MUST provide PostgreSQL, backend, and frontend with health ordering and runtime-only secrets.
- **FR-006**: Backup/restore scripts MUST fail closed on unsafe targets and verify archive/checksum/restored invariants.
- **FR-007**: Logs MUST be structured, correlation-aware, bounded, and sanitized.
- **FR-008**: Liveness and readiness MUST be distinct; Prometheus-compatible metrics MUST avoid high-cardinality personal/resource identifiers.
- **FR-009**: Login MUST have bounded rate limiting with tested recovery and generic failures.
- **FR-010**: CORS, security headers, actuator exposure, and authentication route policy MUST be explicit and tested.
- **FR-011**: Existing server-derived acting-context authorization and cross-branch non-disclosure MUST not regress.
- **FR-012**: Branches MUST carry validated IANA time zones; scheduling/dashboard time semantics MUST use the acting branch zone and document DST behavior.
- **FR-013**: Department reads MUST become acting-context scoped; unknown legacy rows remain hidden.
- **FR-014**: MRN and invoice-number uniqueness scope MUST be explicitly decided and enforced consistently in schema, service, tests, and docs. Phase 4 default: preserve global uniqueness unless safe migration evidence justifies branch scope.
- **FR-015**: Selected legacy String time/money persistence in demonstrated workflows MUST migrate to typed database columns while preserving current wire-format compatibility.
- **FR-016**: OpenAPI MUST be generated from backend annotations/contracts and checked for deterministic drift.
- **FR-017**: Frontend transport MUST use generated/generated-derived typed client contracts for demonstrated workflows.
- **FR-018**: Canonical acceptance MUST test migration, PostgreSQL workflows, concurrency, backup/restore, Compose, dependency failure, restart, security, OpenAPI drift, frontend, browser journey, and documentation.
- **FR-019**: All fixtures and evidence MUST remain synthetic and public-safe.
- **FR-020**: No task may commit, push, deploy, release, process real data, or claim clinical/production/compliance readiness.

### Key Entities

- **Flyway Schema History**: immutable applied-migration ledger.
- **Branch Time Zone**: validated IANA zone attached to each branch.
- **Typed Workflow Values**: timestamps as PostgreSQL timestamp-with-time-zone-compatible Java types and simulated invoice amounts as exact numeric values; wire formats remain stable strings where required.
- **Operational Evidence**: sanitized task/checkpoint/acceptance records, not business data.

## Success Criteria

- **SC-001**: Empty and Phase 3 rehearsal databases migrate successfully; second startup applies zero migrations.
- **SC-002**: All existing 162 backend and 237 frontend tests remain green or are intentionally superseded with equal/stronger coverage, with exact new totals recorded.
- **SC-003**: PostgreSQL concurrency tests repeatedly produce exactly one legal winner and no partial writes.
- **SC-004**: Backup/restore produces matching allowlisted counts/invariants and a verified checksum.
- **SC-005**: Compose reaches healthy state from clean checkout and the full synthetic journey passes.
- **SC-006**: Dependency-loss test shows truthful liveness/readiness separation and recovery after PostgreSQL returns.
- **SC-007**: Security matrix passes with zero unauthorized mutations/audit-success events.
- **SC-008**: OpenAPI generation is deterministic and drift check is green; representative frontend client tests pass.
- **SC-009**: Public tracked artifacts contain no credentials, private topology, real personal data, or unsupported claims.
- **SC-010**: Formal Phase 4 traceability maps every FR and SC to fresh evidence and enforces a stop gate.

## Assumptions

- Docker and a compatible container runtime are available to the executor.
- All PostgreSQL databases used by automated work are disposable and synthetic.
- No external observability service is required; Prometheus-format export and documented local inspection are sufficient.
- No refresh-token system, MFA, SSO, FHIR/HL7/PACS, or real deployment enters this phase.
- Existing public REST response shapes are preserved unless a migration task supplies explicit compatibility tests.
