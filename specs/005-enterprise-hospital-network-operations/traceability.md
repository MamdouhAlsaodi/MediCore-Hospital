# Phase 5 Planning Traceability

**Status:** The tables below are the original planning baseline; their `NOT RUN`
cells are historical, not current verdicts. Current acceptance and the complete
FR/SC-to-evidence mapping are in the **Fresh canonical acceptance mapping**
section at the end of this file and in
`docs/evidence/phase5-verification.md`.

## Functional requirements

| Requirement | Planned tasks | Planned primary evidence | Current status |
|---|---|---|---|
| FR-001, FR-002, FR-003, FR-004 hierarchy/lifecycle | T011–T018, T027–T030, T031–T045 | V5 migration + hierarchy API/security tests | NOT RUN |
| FR-005, FR-006, FR-007, FR-008, FR-009 assignments/context/render isolation | T019–T025, T046–T061 | auth matrix, structural-claim tests, frontend render discrimination | NOT RUN |
| FR-010, FR-011, FR-012, FR-013 patient identity/access | T062–T077 | V6 migration, patient access API tests, PostgreSQL duplicate races | NOT RUN |
| FR-014, FR-015, FR-016, FR-017, FR-018, FR-019, FR-020, FR-021 transfer/reservation/idempotency/concurrency | T078–T103 | V7 migration, lifecycle/API/security tests, PostgreSQL race evidence | NOT RUN |
| FR-022, FR-023 capacity/scheduling | T104–T115 | capacity exact-sum tests and PostgreSQL conflict races | NOT RUN |
| FR-024, FR-025 command center/non-clinical metrics | T116–T126 | dashboard exact-sum/query-count/API/UI tests | NOT RUN |
| FR-026, FR-027, FR-028 audit/non-enumeration/no partial writes | T127–T138 | audit scope matrix, zero-success-audit assertions, telemetry scans | PASS (local; US7 phase — see docs/evidence/phase5-us7-development-log.md) |
| FR-029 OpenAPI/generated client | T040–T042, T054, T075, T101–T102, T112, T122 | deterministic generation and drift checks | NOT RUN |
| FR-030, FR-031 migrations/PostgreSQL-real proof | T014–T016, T028–T030, T063–T064, T072, T081, T099–T100, T155–T158 | empty and Phase 4-shaped migrations, Testcontainers races | NOT RUN |
| FR-032 desktop/mobile browser evidence | T139–T154 | Playwright journeys and context discrimination | NOT RUN |
| FR-033 canonical acceptance | T157–T159, T168–T169 | 15-stage fail-fast command | NOT RUN |
| FR-034 synthetic/public-safe artifacts | T010, T160, T167 | candidate-path scanner and manual triage | NOT RUN |
| FR-035 no implied execution/publication | T001–T005, T170 | authorization record and final intact worktree | PLAN ENFORCED |

## Success criteria

| Criterion | Planned tasks | Pass evidence required | Current status |
|---|---|---|---|
| SC-001 synthetic multi-hospital cohort | T027, T038–T039 | deterministic 3-hospital/2-branch/2-zone fixture assertions | NOT RUN |
| SC-002 hierarchy authorization matrix | T032–T037, T044–T045 | exact allowed/foreign identifiers and counts | NOT RUN |
| SC-003 stale/tampered/disabled authority | T024–T025, T046–T053, T061 | request reload and refusal matrix | NOT RUN |
| SC-004 one patient identity + grants | T062–T077 | migration/API/concurrency evidence | NOT RUN |
| SC-005 complete transfer paths | T078–T103 | happy/reject/cancel lifecycle evidence | NOT RUN |
| SC-006 one race winner/no partial writes | T099–T100, T111, T156 | repeated PostgreSQL race output | NOT RUN |
| SC-007 idempotency replay/conflict | T080, T086–T087, T099–T100, T150 | identical replay/no duplicate + changed-payload `409` | NOT RUN |
| SC-008 exact capacity sums | T104–T115, T117–T121 | branch→hospital→network equality | NOT RUN |
| SC-009 desktop/mobile/no stale paint | T139–T154 | Playwright exact journey and render checks | NOT RUN |
| SC-010 deterministic contracts | T040–T042, T054, T075, T101–T102, T112, T122 | generation twice + zero drift + status matrix | NOT RUN |
| SC-011 Phase 4 non-regression | T005, T029, T044, T061, T077, T103, T115, T126, T138, T168 | fresh canonical/regression outputs | NOT RUN |
| SC-012 public-safe tracked artifacts | T010, T160, T167 | zero unresolved scanner findings | NOT RUN |
| SC-013 canonical pass and cleanup | T157–T159, T168–T170 | all 15 stages exit 0 + zero leftovers | NOT RUN |
| SC-014 complete traceability | T166, T169 | every FR/SC mapped to source/test/runtime/docs | NOT RUN |

## User-story acceptance map

| Story | Task range | Independent checkpoint |
|---|---:|---|
| US1 Multi-hospital hierarchy | T031–T045 | Authorized hierarchy only, deterministic and bounded |
| US2 Hierarchical acting context | T046–T061 | Full ancestor reload and no stale paint |
| US3 Network patient identity | T062–T077 | One identity, explicit hospital grants, no foreign discovery |
| US4 Inter-hospital transfer | T078–T103 | Transactional full lifecycle, reservation, idempotency, races |
| US5 Capacity/scheduling | T104–T115 | Exact authorized aggregates and one-winner conflicts |
| US6 Command center | T116–T126 | Exact network/hospital/branch sums and scoped UI |
| US7 Audit/isolation | T127–T138 | Structured traceability and zero leakage/false success |
| US8 Complete demonstration | T139–T154 | Clean-stack desktop/mobile journey and recovery |

## Planning gate

The planning artifact was complete when its documents existed, tasks were
sequential, and requirements were mapped. This historical planning gate is not
an implementation acceptance verdict; see the fresh acceptance mapping below.

## Fresh canonical acceptance mapping (T166)

**Current verdict (2026-10-12): PASS — local synthetic portfolio scope.** The
fresh canonical run recorded in `docs/evidence/phase5-verification.md` exited
0 with all 15 checkpoints passed. The historical `NOT RUN` cells above are
deliberately retained as the planning snapshot; they are not current verdicts.

Evidence notation: **Direct** means the named canonical stage executed that
test/gate in this run; **coverage pointer** means the source/test documents
what the requirement covers, but is not by itself an independently executed
proof. Stage references resolve to `scripts/phase5/acceptance.sh` stage names;
the combined `compose-health + desktop-mobile-journey + restart-recovery`
checkpoint reports its three sub-results separately in the canonical log.
Passing local synthetic evidence is not production, clinical, regulatory, or
security certification.

### Functional requirements

| Requirement | Concrete implementation / coverage pointers | Direct canonical proof (stage → evidence) | Verdict |
|---|---|---|---|
| FR-001 | `backend/src/main/java/com/mamtrex/hospital/organization/`; `V5__hospital_network_hierarchy.sql`; `NetworkHierarchyApiTest` | `phase4-shaped-migration` → disposable Phase 4-shaped PostgreSQL migration; `backend-suite` → hierarchy API tests | PASS local |
| FR-002 | `backend/src/main/java/com/mamtrex/hospital/auth/BranchAccessService.java`, `NetworkHierarchyService.java`; `NetworkHierarchyApiTest`, `HospitalFacilityTest` | `backend-suite` → hierarchy/ownership tests | PASS local |
| FR-003 | `V5__hospital_network_hierarchy.sql`; `Phase5MigrationIntegrationTest` | `empty-migrations`, `phase4-shaped-migration` → migration test and shaped rehearsal | PASS local |
| FR-004 | `backend/src/main/java/com/mamtrex/hospital/auth/BranchAccessService.java`, `auth/ActingContextService.java`; `NetworkHierarchyApiTest`, `SecurityAuthorizationTest` | `backend-suite` → hierarchy and immediate invalidation tests | PASS local |
| FR-005 | `auth/ActingAssignment.java`, `auth/ActingContextService.java`; `HierarchicalActingContextApiTest` | `backend-suite` → auth/context tests | PASS local |
| FR-006 | `auth/ActingContextService.java`, `auth/JwtFilter.java`; `HierarchicalActingContextApiTest`, `SecurityAuthorizationTest` | `backend-suite` → reload/tamper security tests | PASS local |
| FR-007 | `auth/ActingContextService.java`, `frontend/src/features/network/NetworkContextSelector.jsx`; `HierarchicalActingContextApiTest`, `NetworkContextSelector.test.jsx` | `backend-suite`, `frontend-tests-build`; desktop/mobile journey sub-checkpoint | PASS local |
| FR-008 | `api/openapi/medicore-v1.yaml`, `frontend/src/generated/api/`; `OpenApiContractTest`, acting-context tests | `backend-suite` → OpenAPI contract test; `openapi-drift` → served/tracked byte drift clean | PASS local |
| FR-009 | `frontend/src/auth.js`, `frontend/src/AppShell.jsx`, `frontend/src/DashboardPage.jsx`; `AppShell.test.jsx`, `DashboardPage.test.jsx`, `e2e/phase5-context-discrimination.spec.js` | `frontend-tests-build`; `compose-health + desktop-mobile-journey + restart-recovery` → context-discrimination journeys | PASS local |
| FR-010 | `patient/Patient.java`, `V6__network_patient_identity.sql`; `NetworkPatientIdentityApiTest`, `NetworkPatientConcurrencyIntegrationTest` | `empty-migrations`, `phase4-shaped-migration`, `backend-suite` | PASS local |
| FR-011 | `patient/PatientHospitalAccess.java`, `PatientHospitalAccessRepository.java`; `NetworkPatientIdentityApiTest` | `backend-suite` → grant-scoped visibility tests | PASS local |
| FR-012 | `V6__network_patient_identity.sql`, `DemoDataInitializer.java`; `Phase5MigrationIntegrationTest`, `DemoDataInitializerTest` | `empty-migrations`, `phase4-shaped-migration`, `backend-suite` | PASS local |
| FR-013 | `patient/PatientService.java`; `NetworkPatientIdentityApiTest` | `backend-suite` → server-derived patient access tests | PASS local |
| FR-014 | `transfer/TransferRequest.java`, `V7__transfer_reservation_idempotency.sql`; `TransferWorkflowApiTest` | `backend-suite` → transfer API suite | PASS local |
| FR-015 | `transfer/TransferRequest.java`, `TransferWorkflowApiTest` | `backend-suite` → lifecycle-state tests | PASS local |
| FR-016 | `transfer/TransferAuthorizationMatrixTest`, `TransferWorkflowApiTest` | `backend-suite`, `postgres-concurrency` | PASS local |
| FR-017 | `transfer/TransferService.java`, `TransferBedReservation.java`; `TransferWorkflowApiTest`, `TransferConcurrencyIntegrationTest` | `backend-suite`, `postgres-concurrency` → disposable PostgreSQL race suite | PASS local |
| FR-018 | `transfer/TransferService.java`, `TransferBedReservation.java`; `TransferWorkflowApiTest` | `backend-suite` → cancellation/rejection and reservation tests | PASS local |
| FR-019 | `transfer/TransferService.java`, `TransferWorkflowApiTest` | `backend-suite`, `postgres-concurrency` | PASS local |
| FR-020 | `backend/src/main/java/com/mamtrex/hospital/idempotency/IdempotencyService.java`, `TransferWorkflowApiTest`, `TransferV7IntegrityIntegrationTest` | `backend-suite`, `postgres-concurrency` → PostgreSQL transfer concurrency/integrity | PASS local |
| FR-021 | `V7__transfer_reservation_idempotency.sql`; `TransferConcurrencyIntegrationTest`, `NetworkPatientConcurrencyIntegrationTest`, `TransferV7IntegrityIntegrationTest` | `postgres-concurrency` → disposable PostgreSQL concurrency stage | PASS local |
| FR-022 | `reporting/FacilityCapacityService.java`; `FacilityCapacityApiTest` | `backend-suite` → capacity API tests | PASS local |
| FR-023 | `reporting/FacilityCapacityApiTest`, `TransferConcurrencyIntegrationTest`; bed/admission service tests | `backend-suite`, `postgres-concurrency` | PASS local |
| FR-024 | `reporting/NetworkOperationsDashboardService.java`; `NetworkOperationsDashboardApiTest`, `HospitalNetworkSummary.test.jsx` | `backend-suite`, `frontend-tests-build` | PASS local |
| FR-025 | `reporting/NetworkOperationsDashboardService.java`; `NetworkOperationsDashboardApiTest`, `HospitalNetworkSummary.test.jsx` | `backend-suite`, `frontend-tests-build` | PASS local |
| FR-026 | `audit/NetworkAudit*`, audit schema migration; `NetworkAuditIsolationApiTest` | `backend-suite`, `audit-log-metrics` | PASS local |
| FR-027 | `transfer/TransferService.java`, `NetworkAuditIsolationApiTest`; `TransferWorkflowApiTest` | `backend-suite`, `postgres-concurrency`, `audit-log-metrics` | PASS local |
| FR-028 | `NetworkHierarchyApiTest`, `NetworkPatientIdentityApiTest`, `NetworkAuditIsolationApiTest` | `backend-suite`, `audit-log-metrics` | PASS local |
| FR-029 | `api/openapi/medicore-v1.yaml`, `frontend/src/generated/api/`; `OpenApiContractTest` | `backend-suite` → contract test; `openapi-drift` → no drift | PASS local |
| FR-030 | `backend/src/main/resources/db/migration/V5__hospital_network_hierarchy.sql` through `V7__transfer_reservation_idempotency.sql`; `Phase5MigrationIntegrationTest` | `empty-migrations`, `phase4-shaped-migration` | PASS local |
| FR-031 | `Phase5MigrationIntegrationTest`, `TransferConcurrencyIntegrationTest`, `NetworkPatientConcurrencyIntegrationTest` | `empty-migrations`, `phase4-shaped-migration`, `postgres-concurrency` → disposable PostgreSQL | PASS local |
| FR-032 | `e2e/phase5-*.spec.js`; `frontend/src/AppShell.test.jsx`, `DashboardPage.test.jsx` | `compose-health + desktop-mobile-journey + restart-recovery` → 7 passed, 3 intentional mobile duplicate skips | PASS local |
| FR-033 | `scripts/phase5/acceptance.sh`; this mapping and `docs/evidence/phase5-verification.md` | canonical runner itself: all 15 checkpoints, exit 0; `docs-traceability` | PASS local |
| FR-034 | `scripts/phase5/check-public-artifacts.sh`; synthetic fixtures and evidence docs | `public-artifact-scan` → clean, including negative self-test | PASS local |
| FR-035 | Phase 5 planning scope and stop-gate docs; no commit/push performed | `capability-negative-proof`, `docs-traceability`, `disposable-cleanup-proof`; this run made no commit/push/deploy/release | PASS for enforced boundary only |

### Success criteria

| Criterion | Concrete files / coverage pointers | Direct canonical proof (stage → evidence) | Verdict |
|---|---|---|---|
| SC-001 | `DemoDataInitializer.java`, `DemoDataInitializerTest`, `NetworkHierarchyApiTest` | `backend-suite` → synthetic fixture and deterministic hierarchy tests | PASS local |
| SC-002 | `NetworkHierarchyApiTest`, `NetworkHierarchyService.java` | `backend-suite` → hierarchy auth/non-enumeration matrix | PASS local |
| SC-003 | `HierarchicalActingContextApiTest`, `SecurityAuthorizationTest`, context-discrimination E2E | `backend-suite`, `frontend-tests-build`, desktop/mobile journey sub-checkpoint | PASS local |
| SC-004 | `V6__network_patient_identity.sql`, `NetworkPatientIdentityApiTest`, `NetworkPatientConcurrencyIntegrationTest` | `empty-migrations`, `phase4-shaped-migration`, `backend-suite`, `postgres-concurrency` | PASS local |
| SC-005 | `TransferWorkflowApiTest`, `TransferAuthorizationMatrixTest`, `TransferConcurrencyIntegrationTest` | `backend-suite`, `postgres-concurrency` | PASS local |
| SC-006 | `TransferConcurrencyIntegrationTest`, `NetworkPatientConcurrencyIntegrationTest`; `test-transfer-concurrency.sh` | `postgres-concurrency` → disposable PostgreSQL race run | PASS local |
| SC-007 | `IdempotencyService.java`, `TransferWorkflowApiTest`, `TransferV7IntegrityIntegrationTest` | `backend-suite`, `postgres-concurrency` | PASS local |
| SC-008 | `FacilityCapacityApiTest`, `NetworkOperationsDashboardApiTest`, aggregate service tests | `backend-suite`; dashboard and capacity API proof | PASS local |
| SC-009 | `e2e/phase5-*.spec.js`, `AppShell.test.jsx`, `DashboardPage.test.jsx` | desktop/mobile journey sub-checkpoint → 7 passed, 3 intentional skips; frontend tests | PASS local |
| SC-010 | `OpenApiContractTest`, `api/openapi/medicore-v1.yaml`, generated client | `backend-suite`; `openapi-drift` → clean, exit 0 | PASS local |
| SC-011 | `scripts/phase4/acceptance.sh`, Phase 4/5 evidence documents | `phase4-baseline` → 13/13; `backend-suite` → 392/392; frontend → 343/343 | PASS local |
| SC-012 | `scripts/phase5/check-public-artifacts.sh`, `docs/evidence/phase5-verification.md` | `public-artifact-scan` → clean | PASS local |
| SC-013 | `scripts/phase5/acceptance.sh`, cleanup proof | canonical runner → 15/15, exit 0; `disposable-cleanup-proof` passed | PASS local |
| SC-014 | This per-ID mapping, `docs/evidence/phase5-verification.md`, implementation status | `docs-traceability` → passed; programmatic completeness check recorded in verification evidence | PASS local |

**Scope / stop gate:** These verdicts authorize only a synthetic, local
Training/Portfolio demonstration. The browser run intentionally skipped three
mobile duplicate cases. No production deployment, release, pilot, real-data
use, clinical operation, compliance/security certification, capacity/SLA claim,
or SaaS tenancy is approved or implied. No commit or push is part of this work.
