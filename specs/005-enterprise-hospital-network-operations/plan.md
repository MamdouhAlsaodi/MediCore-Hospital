# Implementation Plan: Phase 5 — Enterprise Hospital Network Operations

> **Planning-only contract:** This document is not execution authorization. Do not delegate, edit source code, install dependencies, run migrations, create a branch, commit, push, open a PR, merge, deploy, or release until Mamdouh explicitly authorizes implementation.

**Goal:** Demonstrate a secure, synthetic, multi-hospital enterprise network workflow inside MediCore: hierarchy and acting authority, one network patient identity with hospital grants, transactional inter-hospital transfer, facility-aware capacity, hospital/network command centers, and complete isolation evidence.

**Architecture:** Preserve the Java/Spring modular monolith, React SPA, generated OpenAPI client, Flyway PostgreSQL authority, and existing server-reloaded acting context. Keep `HospitalOrganization` as the one network; add `HospitalFacility` beneath it, extend assignments/context with hospital scope, migrate patient ownership to network identity plus explicit hospital access, and implement transfer/reservation/idempotency as transactional modules in the same database. Keep UI state tagged by the complete acting-context key and extend grouped dashboard queries without client-derived authority.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Security, Spring Data JPA, Flyway, PostgreSQL, Testcontainers, OpenAPI/springdoc, React 19, JavaScript plus generated TypeScript contracts, Vite 7, Vitest/Testing Library, Playwright, Docker Compose.

**Planning Baseline:** Accepted Phase 4 source commit `2be426438a7519e01b14c99bf198031abc95a760`, merged to `main` by `0c5a303a72b31e15cdac0d41773580a3585605e1`. Implementation must start from a fresh worktree created from then-current `origin/main`, not from this planning worktree.

## Summary

Phase 5 adds an enterprise hierarchy and one complete cross-hospital operational journey without turning MediCore into a clinical product or SaaS platform. The implementation is organized as independently testable user-story increments after a serialized migration/security foundation. Every behavior follows RED → GREEN → REFACTOR; PostgreSQL proves migrations, hierarchy constraints, uniqueness, concurrency, reservations, and idempotency. The final gate extends the canonical local acceptance model with Phase 5 evidence and keeps all claims synthetic, non-clinical, and portfolio-only.

## Technical Context

**Language/Version:** Java 21; React 19 JavaScript; generated TypeScript contract artifacts

**Primary Dependencies:** Spring Boot 3.5.5, Spring Security/JPA/Actuator, Flyway, PostgreSQL, Testcontainers, springdoc-openapi, React, Vite, Vitest, Playwright

**Storage:** PostgreSQL review profile (authoritative for Phase 5 semantics); H2 only for fast developer/unit paths where behavior does not depend on PostgreSQL

**Testing:** Maven/JUnit/Spring MockMvc/Testcontainers; Vitest/Testing Library; Playwright; shell acceptance scripts; OpenAPI deterministic drift checks

**Target Platform:** Local Linux/containerized synthetic review environment

**Project Type:** Web application: Spring Boot backend + React SPA + generated API contract

**Performance Goals:** No invented production throughput. Bounded query count independent of hospital/branch card count; test fixtures must prove no N+1 expansion in hierarchy and dashboard aggregation.

**Constraints:** Synthetic data only; one network, multiple hospitals; no SaaS tenancy; no real integrations; no protected env reads; server-owned hierarchy; migration fail-closed; generic cross-scope denial; no microservices/event broker; no unsupported compliance/clinical/production claim.

**Scale/Scope:** Demonstration fixture of at least 3 hospitals, at least 2 branches per hospital, at least 2 time zones, multiple assignment scopes, and one complete cross-hospital transfer journey.

## Constitution Check

### Pre-design gate

| Principle | Result | Planned evidence |
|---|---|---|
| Truthful Training/Portfolio boundary | PASS | Scope and docs forbid real/clinical/production claims |
| Server-owned authority and isolation | PASS | Full ancestor-chain reload; no client scope authority |
| Test-first, PostgreSQL-real verification | PASS | TDD tasks and Testcontainers concurrency/migration gates |
| Forward-only data safety | PASS | Versioned Flyway migration and malformed-fixture refusal |
| Observable and secure by default | PASS | Bounded structured audit/log fields and leakage tests |
| Contract-first cross-client changes | PASS | OpenAPI first, generated client drift gate before UI adoption |
| Small, reversible, evidence-bearing steps | PASS | Dependency-ordered tasks and per-checkpoint evidence |
| Phase 4 hierarchy constraint | REQUIRES OWNER-APPROVED AMENDMENT BEFORE IMPLEMENTATION | `clarifications.md` specifies the narrow Phase 5 amendment |

**Gate result:** Planning may proceed. Implementation is BLOCKED until the Phase 5 constitution amendment is explicitly approved and recorded.

### Post-design re-check

The design preserves all core principles. The only deliberate governance change is one network containing multiple hospitals/branches instead of one organization with three branches. It does not introduce customer tenancy. No complexity exception is required for microservices, broker, distributed data, or a new frontend architecture because all were rejected.

## Project Structure

### Documentation (this feature)

```text
specs/005-enterprise-hospital-network-operations/
├── spec.md
├── clarifications.md
├── research.md
├── data-model.md
├── plan.md
├── quickstart.md
├── traceability.md
├── contracts/
│   └── phase5-api.yaml
└── tasks.md
```

### Planned source paths

```text
backend/src/main/java/com/mamtrex/hospital/
├── organization/
│   ├── HospitalFacility.java
│   ├── HospitalFacilityRepository.java
│   ├── NetworkHierarchyDtos.java
│   ├── NetworkHierarchyService.java
│   └── NetworkHierarchyController.java
├── auth/
│   ├── AssignmentScope.java
│   ├── ActingAssignment.java
│   ├── ActingContext.java
│   ├── ActingContextService.java
│   ├── BranchAccessService.java
│   └── JwtService.java
├── patient/
│   ├── Patient.java
│   ├── PatientHospitalAccess.java
│   ├── PatientHospitalAccessRepository.java
│   ├── PatientRepository.java
│   └── PatientService.java
├── transfer/
│   ├── TransferRequest.java
│   ├── TransferStatus.java
│   ├── TransferBedReservation.java
│   ├── TransferDtos.java
│   ├── TransferRepository.java
│   ├── TransferBedReservationRepository.java
│   ├── TransferAuthorizationService.java
│   ├── TransferService.java
│   └── TransferController.java
├── idempotency/
│   ├── IdempotencyRecord.java
│   ├── IdempotencyRecordRepository.java
│   └── IdempotencyService.java
├── reporting/
│   ├── NetworkOperationsDashboardService.java
│   ├── DashboardDtos.java
│   └── DashboardController.java
└── audit/
    ├── AuditEvent.java
    ├── AuditService.java
    └── AuditEventRepository.java

backend/src/main/resources/db/migration/
├── V5__hospital_network_hierarchy.sql
├── V6__network_patient_identity.sql
└── V7__transfer_reservation_idempotency.sql

backend/src/test/java/com/mamtrex/hospital/
├── organization/NetworkHierarchyApiTest.java
├── auth/HierarchicalActingContextApiTest.java
├── patient/NetworkPatientIdentityApiTest.java
├── transfer/TransferWorkflowApiTest.java
├── transfer/TransferAuthorizationMatrixTest.java
├── infrastructure/Phase5MigrationIntegrationTest.java
├── infrastructure/TransferConcurrencyIntegrationTest.java
├── reporting/NetworkOperationsDashboardApiTest.java
└── audit/NetworkAuditIsolationApiTest.java

frontend/src/
├── features/network/
│   ├── NetworkContextSelector.jsx
│   ├── NetworkContextSelector.test.jsx
│   ├── networkApi.js
│   └── hierarchyDisplayState.js
├── features/transfers/
│   ├── TransfersPage.jsx
│   ├── TransferDetail.jsx
│   ├── TransferForm.jsx
│   ├── transferApi.js
│   └── *.test.jsx
├── features/dashboard/
│   ├── dashboardApi.js
│   └── HospitalNetworkSummary.jsx
├── generated/api/
├── AppShell.jsx
├── DashboardPage.jsx
├── navigation.js
└── auth.js

e2e/
├── phase5-network-journey.spec.js
├── phase5-context-discrimination.spec.js
└── phase5-transfer-concurrency.spec.js

scripts/phase5/
├── check-public-artifacts.sh
├── migrate-phase4-shaped-postgres.sh
├── test-transfer-concurrency.sh
├── container-network-journey.sh
└── acceptance.sh
```

**Structure Decision:** Extend existing bounded packages. `transfer` owns lifecycle/reservation orchestration; `idempotency` is narrow reusable command infrastructure; `organization` owns hierarchy; `reporting` owns aggregation. Controllers parse/map only. No generic workflow engine, policy DSL, event broker, or second application is introduced.

## Delivery Phases and Gates

### Phase A — Governance and baseline

- Approve the narrow constitution amendment.
- Create fresh Phase 5 worktree/branch from current `origin/main` only after explicit implementation authorization.
- Capture clean baseline, exact test totals, schema inventory, and public-artifact scan.
- Gate: accepted Phase 4 behavior passes before Phase 5 source changes.

### Phase B — Hierarchy and migration foundation

- Add hospitals, branch ownership, assignment/audit hospital context, deterministic legacy backfill, and malformed-fixture refusals.
- Gate: empty + Phase 4-shaped PostgreSQL migration is restart-idempotent; ancestor-chain constraints hold.

### Phase C — Hierarchical authority and UI context

- Extend assignment/context/JWT/session contracts and selector with hospital level.
- Gate: complete authorization matrix and render-phase discrimination pass.

### Phase D — Network patient identity

- Migrate patient ownership; create hospital access grants; make all patient routes grant-aware.
- Gate: one identity, no foreign discovery, migration/access tests green.

### Phase E — Transfer workflow (MVP enterprise journey)

- Add transfer state machine, authorization matrix, bed reservation, destination grant, idempotency, and transactional audits.
- Gate: happy/reject/cancel paths and repeated PostgreSQL races pass.

### Phase F — Capacity and command centers

- Add server-derived hospital/network capacity and command-center aggregation.
- Gate: exact sum invariants, bounded queries, and scoped UI drill-down pass.

### Phase G — Evidence and stop gate

- OpenAPI/client drift, full desktop/mobile journey, restart/recovery, public-safety scan, traceability, canonical acceptance.
- Gate: all FR/SC evidence fresh; otherwise terminal status is PARTIAL/BLOCKED.

## API and Compatibility Strategy

1. Change backend annotations/contracts and contract tests first.
2. Regenerate `api/openapi/medicore-v1.yaml` and `frontend/src/generated/api/` deterministically.
3. Adapt transport modules, then UI.
4. Preserve existing branch dashboard and patient wire fields unless the Phase 5 contract explicitly adds fields.
5. Existing `ORGANIZATION` remains a valid scope value; new clients may display it as “Network”.
6. Foreign identifiers never produce richer errors than the existing non-enumeration policy.

## Migration Strategy

- Use V5/V6/V7 forward-only migrations.
- V5 creates hospital hierarchy and backfills current synthetic branches to a known hospital.
- V6 creates network patient ownership and access grants from verified branch ownership.
- V7 creates transfers, reservations, idempotency, indexes, and lifecycle constraints.
- A Phase 4-shaped disposable fixture must prove counts, references, ownership, and restart idempotency.
- Any orphan, cross-network link, duplicate future key, or unknown owner blocks migration; no guessed repair.

## Test Strategy

- Every behavior task starts with a failing focused test.
- Real PostgreSQL is mandatory for Flyway, unique/partial indexes, locking, optimistic versioning, reservation races, and idempotency races.
- Focused H2/API tests cover fast authorization and serialization where database semantics are not claimed.
- Contract tests pin paths, payloads, state enums, headers, and `200/400/401/403/404/409` responses.
- Frontend tests pin context-tagged rendering, loading/error/empty/conflict states, accessible selector/forms, and responsive layout.
- Playwright uses process-local synthetic credentials, desktop/mobile viewports, exact non-2xx assertions, and clean stack teardown.
- Canonical acceptance fails closed on missing capabilities; no silent skip may claim Phase 5 PASS.

## Security and Privacy Invariants

- Never read `/home/server/.config/medicore/runtime.env` or `/home/server/medicore-runtime/trial.env`.
- Never print credentials, tokens, connection strings, request bodies, patient names/contact fields, or private topology.
- JWT claims point to assignment/context but do not grant authority; server reload is authoritative.
- Network/hospital/branch/department IDs must match one active chain.
- Patient visibility requires an active hospital grant.
- Denied/conflicted commands leave data unchanged and create no success audit.
- Idempotency keys are bounded storage keys, not logs/metric labels.

## Complexity Tracking

No constitution complexity violation is accepted. The design deliberately avoids microservices, broker/outbox, policy engine, generic resource scheduler, or SaaS tenant layer. The dedicated reservation and idempotency tables are required because concurrency and retry invariants cannot be represented safely by UI state or free-form audit text.

## Stop Conditions

Implementation must stop and report `BLOCKED` if:

- The constitution amendment is not approved.
- The implementation base is not current `origin/main` or has unrelated changes.
- Migration cannot deterministically derive hospital/patient ownership.
- Any test requires real credentials or non-disposable data.
- A security/isolation/migration/concurrency/contract gate fails.
- Requested work would add real clinical semantics, SaaS tenancy, external integration, or publication side effects.

## Definition of Planned Completion

Phase 5 may be called complete only when every SC-001..SC-014 has fresh evidence, canonical acceptance exits `0`, changed paths are within approved scope, public artifacts are clean, and documentation states the exact synthetic/non-clinical limits. Passing this plan never authorizes deployment, release, or real use.
