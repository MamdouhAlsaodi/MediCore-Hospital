# Tasks: Phase 5 — Enterprise Hospital Network Operations

**Input:** `spec.md`, `clarifications.md`, `research.md`, `data-model.md`, `plan.md`, `quickstart.md`, `contracts/phase5-api.yaml`

**Authorization:** Planning artifact only. Every checkbox remains unchecked. Do not execute, delegate, install, migrate, commit, push, open a PR, merge, deploy, or release without a later explicit owner instruction.

**Tests:** Required. Every behavior task follows RED → GREEN → REFACTOR. PostgreSQL-real evidence is mandatory for migrations, constraints, locking, races, reservations, and idempotency.

## Format

- `[P]` = may proceed in parallel only after its phase prerequisites are green and it touches no shared contract/migration/security file.
- `[USn]` = maps to the corresponding user story in `spec.md`.
- Every implementation checkpoint records changed paths, exact commands/results, risks, and `PASS | PARTIAL | BLOCKED` in a local ignored Phase 5 execution report.

## Phase 1 — Governance, branch, and baseline (blocks all source work)

- [x] T001 Obtain explicit owner authorization for Phase 5 implementation and record it in `specs/005-enterprise-hospital-network-operations/implementation-authorization.md`
- [x] T002 Draft and obtain owner approval for the narrow Phase 5 constitution amendment in `.specify/memory/constitution.md`
- [x] T003 Create a fresh `phase5/enterprise-hospital-network-operations` worktree from then-current `origin/main` and verify no unrelated delta with `git status --short`
- [x] T004 Record the exact accepted base SHA and tool versions in ignored `artifacts/phase5/execution-report.md`
- [x] T005 Run the existing canonical Phase 4 acceptance from the fresh base and record exact stage/test totals in ignored `artifacts/phase5/execution-report.md`
- [x] T006 [P] Inventory Phase 4 tables, constraints, indexes, Flyway history, and ownership columns in `specs/005-enterprise-hospital-network-operations/schema-baseline.md`
- [x] T007 [P] Inventory current auth/JWT/session/OpenAPI/frontend context contracts in `specs/005-enterprise-hospital-network-operations/contract-baseline.md`
- [x] T008 [P] Inventory patient/admission/bed/dashboard/audit dependencies in `specs/005-enterprise-hospital-network-operations/domain-baseline.md`
- [x] T009 Add Phase 5 planning/execution artifact exclusions without masking source in `.gitignore`
- [x] T010 Create RED public-artifact path/content checks for Phase 5 candidate files in `scripts/phase5/check-public-artifacts.sh`

**Checkpoint:** Current `main` is green, the constitution amendment is approved, and the baseline/provenance is complete. Otherwise stop.

## Phase 2 — Foundational hierarchy, migration, and contract seams (blocks all user stories)

- [x] T011 Add RED entity/constraint tests for hospital code, IANA zone, active state, and organization ownership in `backend/src/test/java/com/mamtrex/hospital/organization/HospitalFacilityTest.java`
- [x] T012 Create `HospitalFacility` with fields/constraints exactly matching `data-model.md` in `backend/src/main/java/com/mamtrex/hospital/organization/HospitalFacility.java`
- [x] T013 Create deterministic organization/hospital repository queries in `backend/src/main/java/com/mamtrex/hospital/organization/HospitalFacilityRepository.java`
- [x] T014 Add RED Phase 4-shaped migration tests for hospital backfill and malformed hierarchy refusal in `backend/src/test/java/com/mamtrex/hospital/infrastructure/Phase5MigrationIntegrationTest.java`
- [x] T015 Add V5 hospital hierarchy tables/columns/backfill/constraints in `backend/src/main/resources/db/migration/V5__hospital_network_hierarchy.sql`
- [x] T016 Add PostgreSQL metadata assertions for V5 foreign keys, unique keys, indexes, and non-null ownership in `backend/src/test/java/com/mamtrex/hospital/infrastructure/Phase5MigrationIntegrationTest.java`
- [x] T017 Modify `Branch` to require `HospitalFacility` while preserving validated organization consistency in `backend/src/main/java/com/mamtrex/hospital/organization/Branch.java`
- [x] T018 Modify branch repository queries to support hospital and organization ancestor filters in `backend/src/main/java/com/mamtrex/hospital/organization/BranchRepository.java`
- [x] T019 Add `HOSPITAL` while preserving `ORGANIZATION` wire compatibility in `backend/src/main/java/com/mamtrex/hospital/auth/AssignmentScope.java`
- [x] T020 Add RED valid/invalid assignment-shape tests for all four scopes in `backend/src/test/java/com/mamtrex/hospital/auth/HierarchicalAssignmentInvariantTest.java`
- [x] T021 Extend `ActingAssignment` with hospital ownership and scope factories in `backend/src/main/java/com/mamtrex/hospital/auth/ActingAssignment.java`
- [x] T022 Replace null-sensitive logical uniqueness with a PostgreSQL-safe assignment uniqueness strategy in `backend/src/main/resources/db/migration/V5__hospital_network_hierarchy.sql`
- [x] T023 Extend `ActingContext` with non-null `hospitalId` in `backend/src/main/java/com/mamtrex/hospital/auth/ActingContext.java`
- [x] T024 Add RED JWT structural-claim tests covering hospital tampering/staleness in `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java`
- [x] T025 Extend structural claims and token issuance with `hospitalId` in `backend/src/main/java/com/mamtrex/hospital/auth/JwtService.java`
- [x] T026 Extend audit schema/entity with acting hospital and bounded transfer context columns in `backend/src/main/resources/db/migration/V5__hospital_network_hierarchy.sql` and `backend/src/main/java/com/mamtrex/hospital/audit/AuditEvent.java`
- [x] T027 Update synthetic initializer to create one legacy hospital and preserve the Phase 4 cohort in `backend/src/main/java/com/mamtrex/hospital/bootstrap/DemoDataInitializer.java`
- [x] T028 Update V5 empty-schema and second-start idempotency assertions in `backend/src/test/java/com/mamtrex/hospital/infrastructure/FlywayPostgresIntegrationTest.java`
- [x] T029 Run focused hierarchy/migration tests and then the full backend suite from `backend/pom.xml`
- [x] T030 Record V5 migration counts/invariants and verify no protected env or non-disposable database was accessed in ignored `artifacts/phase5/execution-report.md`

**Checkpoint:** V5 migrates empty and Phase 4-shaped disposable PostgreSQL, rejects malformed ownership, and the full backend suite is green.

## Phase 3 — User Story 1: Multi-hospital hierarchy (P1)

**Goal:** Return exactly the authorized network → hospital → branch hierarchy.

**Independent test:** Three-hospital fixture; network, hospital, branch, and foreign-ID matrix returns only allowed descendants.

- [x] T031 [P] [US1] Add RED hierarchy DTO serialization tests in `backend/src/test/java/com/mamtrex/hospital/organization/NetworkHierarchyDtosTest.java`
- [x] T032 [P] [US1] Add RED hierarchy authorization/API tests in `backend/src/test/java/com/mamtrex/hospital/organization/NetworkHierarchyApiTest.java`
- [x] T033 [US1] Create strict hierarchy response records in `backend/src/main/java/com/mamtrex/hospital/organization/NetworkHierarchyDtos.java`
- [x] T034 [US1] Implement explicit authorized hospital/branch-set derivation in `backend/src/main/java/com/mamtrex/hospital/organization/NetworkHierarchyService.java`
- [x] T035 [US1] Add `GET /api/network/hierarchy` mapping only in `backend/src/main/java/com/mamtrex/hospital/organization/NetworkHierarchyController.java`
- [x] T036 [US1] Add hierarchy endpoint authorization rules without client-expanded scope in `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java`
- [x] T037 [US1] Add bounded-query-count assertions for hierarchy loading in `backend/src/test/java/com/mamtrex/hospital/organization/NetworkHierarchyApiTest.java`
- [x] T038 [US1] Expand synthetic fixture to at least three hospitals/two branches each/two zones in `backend/src/main/java/com/mamtrex/hospital/bootstrap/DemoDataInitializer.java`
- [x] T039 [US1] Add deterministic fixture and restart assertions in `backend/src/test/java/com/mamtrex/hospital/bootstrap/DemoDataInitializerTest.java`
- [x] T040 [US1] Annotate hierarchy contracts and expected errors in `backend/src/main/java/com/mamtrex/hospital/organization/NetworkHierarchyController.java`
- [x] T041 [US1] Regenerate and inspect `api/openapi/medicore-v1.yaml` and `frontend/src/generated/api/` using `scripts/phase4/generate-openapi-client.mjs`
- [x] T042 [P] [US1] Add generated transport tests for hierarchy success/denial in `frontend/src/test-utils/generatedApiTransport.test.js`
- [x] T043 [US1] Create hierarchy transport adapter in `frontend/src/features/network/networkApi.js`
- [x] T044 [US1] Run focused backend hierarchy tests, `scripts/phase4/check-openapi-drift.sh`, frontend transport tests, and full regressions from `backend/pom.xml` and `frontend/package.json`
- [x] T045 [US1] Record hierarchy matrix and deterministic ordering evidence in `docs/evidence/phase5-verification.md`

**Checkpoint:** US1 independently proves hierarchy and non-enumeration.

## Phase 4 — User Story 2: Hierarchical acting context and safe switching (P1)

**Goal:** Bind every request to one valid network/hospital/branch chain and prevent stale render data.

**Independent test:** All scope shapes, claim tampering, disabled ancestors, and render-phase race matrix.

- [x] T046 [P] [US2] Add RED login/session/context response tests for hospital fields in `backend/src/test/java/com/mamtrex/hospital/auth/HierarchicalActingContextApiTest.java`
- [x] T047 [P] [US2] Add RED ancestor-chain refusal tests in `backend/src/test/java/com/mamtrex/hospital/auth/HierarchicalActingContextApiTest.java`
- [x] T048 [US2] Extend `BranchAccessService` into full hierarchy validation without trusting client IDs in `backend/src/main/java/com/mamtrex/hospital/auth/BranchAccessService.java`
- [x] T049 [US2] Extend login assignment resolution for organization/network and hospital scope in `backend/src/main/java/com/mamtrex/hospital/auth/ActingContextService.java`
- [x] T050 [US2] Extend context switch to require/validate target hospital and branch in `backend/src/main/java/com/mamtrex/hospital/auth/ActingContextService.java`
- [x] T051 [US2] Extend auth request/response DTOs with hospital fields in `backend/src/main/java/com/mamtrex/hospital/auth/AuthController.java`
- [x] T052 [US2] Update per-request reload structural equality with hospital ID in `backend/src/main/java/com/mamtrex/hospital/auth/ActingContextService.java`
- [x] T053 [US2] Add disabled hospital/branch/assignment immediate-invalidation tests in `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java`
- [x] T054 [US2] Regenerate OpenAPI/client and prove deterministic auth contract drift using `scripts/phase4/check-openapi-drift.sh`
- [x] T055 [P] [US2] Extend context key and session parsers with hospital ID in `frontend/src/auth.js` and `frontend/src/authorization.test.js`
- [x] T056 [P] [US2] Add RED selector target derivation tests in `frontend/src/features/network/NetworkContextSelector.test.jsx`
- [x] T057 [US2] Implement hierarchy-aware selector with server-issued targets in `frontend/src/features/network/NetworkContextSelector.jsx`
- [x] T058 [US2] Integrate hospital context labels and selector into `frontend/src/AppShell.jsx`
- [x] T059 [US2] Replace branch-only key usage with full assignment/hospital/branch/department key in `frontend/src/auth.js` and context-aware screens
- [x] T060 [US2] Add render-phase discrimination tests for late hierarchy/dashboard responses in `frontend/src/AppShell.test.jsx` and `frontend/src/DashboardPage.test.jsx`
- [x] T061 [US2] Run full auth/security matrix from `backend/pom.xml` and frontend tests/typecheck/build plus stale-render tests from `frontend/package.json`

**Checkpoint:** US2 independently proves server-owned hierarchy authority and zero stale-context paint.

## Phase 5 — User Story 3: Network patient identity with hospital access (P1)

**Goal:** One network patient identity, visible only through explicit hospital access.

**Independent test:** A creates, B cannot discover, transfer grant enables B, C remains unable, duplicate race yields one row.

- [ ] T062 [P] [US3] Add RED patient identity/access entity tests in `backend/src/test/java/com/mamtrex/hospital/patient/NetworkPatientIdentityApiTest.java`
- [ ] T063 [P] [US3] Add RED Phase 4 patient backfill/malformed-owner migration tests in `backend/src/test/java/com/mamtrex/hospital/infrastructure/Phase5MigrationIntegrationTest.java`
- [ ] T064 [US3] Add patient network ownership and access table/backfill/constraints in `backend/src/main/resources/db/migration/V6__network_patient_identity.sql`
- [ ] T065 [US3] Modify `Patient` from branch authority to organization/network authority in `backend/src/main/java/com/mamtrex/hospital/patient/Patient.java`
- [ ] T066 [US3] Create `PatientHospitalAccess` exactly per `data-model.md` in `backend/src/main/java/com/mamtrex/hospital/patient/PatientHospitalAccess.java`
- [ ] T067 [US3] Create scoped access queries in `backend/src/main/java/com/mamtrex/hospital/patient/PatientHospitalAccessRepository.java`
- [ ] T068 [US3] Replace patient repository branch filters with network identity plus hospital-access joins in `backend/src/main/java/com/mamtrex/hospital/patient/PatientRepository.java`
- [ ] T069 [US3] Modify patient create to derive organization/hospital and create one local registration grant atomically in `backend/src/main/java/com/mamtrex/hospital/patient/PatientService.java`
- [ ] T070 [US3] Modify patient search/read/update to require active hospital access in `backend/src/main/java/com/mamtrex/hospital/patient/PatientService.java`
- [ ] T071 [US3] Add generic foreign hospital not-found/refusal behavior in `backend/src/main/java/com/mamtrex/hospital/patient/PatientController.java`
- [ ] T072 [US3] Add real PostgreSQL duplicate MRN/access-grant races in `backend/src/test/java/com/mamtrex/hospital/infrastructure/NetworkPatientConcurrencyIntegrationTest.java`
- [ ] T073 [US3] Update admission/appointment/emergency/invoice patient lookups to validate hospital access in their existing service classes under `backend/src/main/java/com/mamtrex/hospital/`
- [ ] T074 [US3] Add cross-hospital regression matrix for dependent workflows in `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java`
- [ ] T075 [US3] Update patient contract tests/OpenAPI/generated client in `backend/src/test/java/com/mamtrex/hospital/contract/OpenApiContractTest.java` and generated paths
- [ ] T076 [P] [US3] Add frontend patient invisibility/access tests in `frontend/src/features/patients/PatientsPage.test.jsx`
- [ ] T077 [US3] Run migration, patient concurrency, dependent workflow, and full backend gates from `backend/pom.xml`, then OpenAPI/frontend gates from `scripts/phase4/check-openapi-drift.sh` and `frontend/package.json`

**Checkpoint:** US3 proves one identity without cross-hospital discoverability.

## Phase 6 — User Story 4: Inter-hospital transfer (P1, enterprise MVP)

**Goal:** Complete transactional request → accept/reserve → transit → complete plus reject/cancel/idempotency paths.

**Independent test:** Full lifecycle and negative/race matrix on disposable PostgreSQL.

- [ ] T078 [P] [US4] Add RED transfer status transition table tests in `backend/src/test/java/com/mamtrex/hospital/transfer/TransferRequestTest.java`
- [ ] T079 [P] [US4] Add RED reservation ownership/lifecycle tests in `backend/src/test/java/com/mamtrex/hospital/transfer/TransferBedReservationTest.java`
- [ ] T080 [P] [US4] Add RED idempotency replay/payload-conflict tests in `backend/src/test/java/com/mamtrex/hospital/idempotency/IdempotencyServiceTest.java`
- [ ] T081 [US4] Add transfer/reservation/idempotency tables, checks, FKs, indexes, and partial unique reservation index in `backend/src/main/resources/db/migration/V7__transfer_reservation_idempotency.sql`
- [ ] T082 [US4] Create `TransferStatus` state enum in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferStatus.java`
- [ ] T083 [US4] Create transfer aggregate with guarded transitions and optimistic version in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferRequest.java`
- [ ] T084 [US4] Create reservation aggregate with ACTIVE/CONSUMED/RELEASED invariants in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferBedReservation.java`
- [ ] T085 [US4] Create transfer and reservation repositories with scoped/locking queries in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferRepository.java` and `TransferBedReservationRepository.java`
- [ ] T086 [US4] Create idempotency entity/repository with normalized unique key in `backend/src/main/java/com/mamtrex/hospital/idempotency/IdempotencyRecord.java` and `IdempotencyRecordRepository.java`
- [ ] T087 [US4] Implement bounded request fingerprint/replay/conflict semantics in `backend/src/main/java/com/mamtrex/hospital/idempotency/IdempotencyService.java`
- [ ] T088 [P] [US4] Add RED source/destination/foreign-role matrix in `backend/src/test/java/com/mamtrex/hospital/transfer/TransferAuthorizationMatrixTest.java`
- [ ] T089 [US4] Implement role/scope/source/destination authorization in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferAuthorizationService.java`
- [ ] T090 [P] [US4] Add RED request/list/get API tests in `backend/src/test/java/com/mamtrex/hospital/transfer/TransferWorkflowApiTest.java`
- [ ] T091 [US4] Create strict request/response DTOs with bounded reason codes and versions in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferDtos.java`
- [ ] T092 [US4] Implement REQUESTED creation/list/get with server-derived source ownership in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferService.java`
- [ ] T093 [US4] Implement destination accept transaction: validate, reserve, grant access, transition, audit in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferService.java`
- [ ] T094 [US4] Implement reject and cancel transactions with exact reservation ownership release in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferService.java`
- [ ] T095 [US4] Implement start-transit transaction and source admission/bed handoff in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferService.java`
- [ ] T096 [US4] Implement completion transaction: destination admission, reservation consumption, bed occupation, final audit in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferService.java`
- [ ] T097 [US4] Map transfer routes and mandatory idempotency headers in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferController.java`
- [ ] T098 [US4] Add transfer route security boundaries in `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java`
- [ ] T099 [US4] Add repeated real PostgreSQL bed/transition/idempotency races in `backend/src/test/java/com/mamtrex/hospital/infrastructure/TransferConcurrencyIntegrationTest.java`
- [ ] T100 [US4] Assert every loser has `409`, zero partial rows, and zero false success audits in `backend/src/test/java/com/mamtrex/hospital/infrastructure/TransferConcurrencyIntegrationTest.java`
- [ ] T101 [US4] Add exact transfer paths/schemas/status/errors to OpenAPI annotations and `backend/src/test/java/com/mamtrex/hospital/contract/OpenApiContractTest.java`
- [ ] T102 [US4] Regenerate OpenAPI/client twice and prove byte stability using `scripts/phase4/check-openapi-drift.sh`
- [ ] T103 [US4] Run transfer unit/API/security/PostgreSQL concurrency/full backend gates from `backend/pom.xml` and record exact repeats/results in ignored `artifacts/phase5/execution-report.md`

**Checkpoint:** US4 is the independently demonstrable Phase 5 enterprise MVP.

## Phase 7 — User Story 5: Facility-aware capacity and scheduling (P2)

**Goal:** Exact authorized branch/hospital/network capacity with no double booking.

**Independent test:** Aggregation sums plus repeated reservation/appointment conflict races.

- [ ] T104 [P] [US5] Add RED capacity DTO and sum-invariant tests in `backend/src/test/java/com/mamtrex/hospital/reporting/FacilityCapacityApiTest.java`
- [ ] T105 [P] [US5] Add RED foreign-hospital capacity refusal tests in `backend/src/test/java/com/mamtrex/hospital/reporting/FacilityCapacityApiTest.java`
- [ ] T106 [US5] Add grouped transfer-reservation counts in `backend/src/main/java/com/mamtrex/hospital/transfer/TransferBedReservationRepository.java`
- [ ] T107 [US5] Add hospital-aware grouped bed/appointment queries in `backend/src/main/java/com/mamtrex/hospital/bed/BedRepository.java` and `appointment/AppointmentRepository.java`
- [ ] T108 [US5] Implement branch/hospital/network capacity aggregation from explicit authorized branch sets in `backend/src/main/java/com/mamtrex/hospital/reporting/FacilityCapacityService.java`
- [ ] T109 [US5] Add `/api/capacity/branch|hospital|network` mappings in `backend/src/main/java/com/mamtrex/hospital/reporting/FacilityCapacityController.java`
- [ ] T110 [US5] Preserve half-open scheduling semantics and validate hospital ancestry in `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentService.java`
- [ ] T111 [US5] Extend PostgreSQL appointment/bed/reservation race coverage in `backend/src/test/java/com/mamtrex/hospital/infrastructure/PostgresConcurrencyIntegrationTest.java`
- [ ] T112 [US5] Add capacity OpenAPI/generated-client contracts and transport tests in `backend/src/test/java/com/mamtrex/hospital/contract/OpenApiContractTest.java` and `frontend/src/test-utils/generatedApiTransport.test.js`
- [ ] T113 [P] [US5] Create capacity adapter in `frontend/src/features/dashboard/capacityApi.js`
- [ ] T114 [US5] Render bounded hospital capacity states in `frontend/src/features/dashboard/HospitalCapacitySummary.jsx`
- [ ] T115 [US5] Run exact aggregation, query-count, concurrency, and backend regressions from `backend/pom.xml`, then frontend gates from `frontend/package.json`

**Checkpoint:** US5 proves facility-aware capacity without persisted aggregate drift.

## Phase 8 — User Story 6: Hospital/network command center (P2)

**Goal:** Network → hospital → branch operational summaries with exact totals and scoped drill-down.

**Independent test:** Three hospitals with unique values; totals and UI rows match authorized source exactly.

- [ ] T116 [P] [US6] Add RED hospital/network DTO contract tests in `backend/src/test/java/com/mamtrex/hospital/reporting/NetworkOperationsDashboardApiTest.java`
- [ ] T117 [P] [US6] Add RED exact-sum and inactive/legacy exclusion tests in `backend/src/test/java/com/mamtrex/hospital/reporting/NetworkOperationsDashboardApiTest.java`
- [ ] T118 [US6] Extend dashboard DTOs with hospital summaries and bounded transfer/capacity metrics in `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardDtos.java`
- [ ] T119 [US6] Extract hospital/network aggregation from broad existing service into `backend/src/main/java/com/mamtrex/hospital/reporting/NetworkOperationsDashboardService.java`
- [ ] T120 [US6] Keep branch summary compatibility while mapping hospital/network endpoints in `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardController.java`
- [ ] T121 [US6] Add bounded query-count assertions independent of hospital/branch card count in `backend/src/test/java/com/mamtrex/hospital/reporting/NetworkOperationsDashboardApiTest.java`
- [ ] T122 [US6] Extend dashboard OpenAPI/client and `frontend/src/features/dashboard/dashboardApi.js`
- [ ] T123 [P] [US6] Add RED hospital/network render and drill-down tests in `frontend/src/DashboardPage.test.jsx`
- [ ] T124 [US6] Implement hospital/network summary component in `frontend/src/features/dashboard/HospitalNetworkSummary.jsx`
- [ ] T125 [US6] Integrate scoped drill-down and context-tagged state in `frontend/src/DashboardPage.jsx`
- [ ] T126 [US6] Run dashboard API/query-count/full backend gates from `backend/pom.xml` and responsive frontend gates from `frontend/package.json`

**Checkpoint:** US6 proves exact operational aggregation and scoped visibility.

## Phase 9 — User Story 7: Audit, isolation, and security evidence (P2)

**Goal:** Trace every successful network operation while proving denials leak and mutate nothing.

**Independent test:** Full allowed/denied matrix with audit-context and telemetry leakage assertions.

- [ ] T127 [P] [US7] Add RED hospital-aware audit read matrix in `backend/src/test/java/com/mamtrex/hospital/audit/NetworkAuditIsolationApiTest.java`
- [ ] T128 [P] [US7] Add RED transfer audit field and forbidden-payload tests in `backend/src/test/java/com/mamtrex/hospital/audit/NetworkAuditIsolationApiTest.java`
- [ ] T129 [US7] Extend audit recording API with structured hospital/transfer context in `backend/src/main/java/com/mamtrex/hospital/audit/AuditService.java`
- [ ] T130 [US7] Add network/hospital/branch scoped audit repository queries in `backend/src/main/java/com/mamtrex/hospital/audit/AuditEventRepository.java`
- [ ] T131 [US7] Extend audit DTO/controller allowlists without patient payloads in `backend/src/main/java/com/mamtrex/hospital/audit/AuditController.java`
- [ ] T132 [US7] Add denied/conflicted zero-success-audit assertions across transfer/patient/context tests under `backend/src/test/java/com/mamtrex/hospital/`
- [ ] T133 [US7] Add hospital/network role and route combinations to `backend/src/test/java/com/mamtrex/hospital/auth/HttpSecurityBoundaryMatrixTest.java`
- [ ] T134 [US7] Add structured-log canaries for hospital/transfer operations in `backend/src/test/java/com/mamtrex/hospital/observability/StructuredLogSanitizationIntegrationTest.java`
- [ ] T135 [US7] Assert no hospital/patient/transfer/assignment/correlation IDs become metric labels in `backend/src/test/java/com/mamtrex/hospital/observability/MetricsBoundaryIntegrationTest.java`
- [ ] T136 [P] [US7] Extend audit UI filters/render isolation tests in `frontend/src/features/audit/AuditPage.test.jsx`
- [ ] T137 [US7] Extend audit adapter/page with authorized hospital context in `frontend/src/features/audit/auditApi.js` and `AuditPage.jsx`
- [ ] T138 [US7] Run security/audit/log/metrics/full backend gates from `backend/pom.xml` and frontend audit gates from `frontend/package.json`

**Checkpoint:** US7 proves traceability without widening data or telemetry exposure.

## Phase 10 — User Story 8: Complete browser/API demonstration (P3)

**Goal:** Reproducible desktop/mobile multi-hospital journey and canonical Phase 5 proof.

**Independent test:** Clean disposable stack executes all Phase 5 scenarios and cleans up fully.

- [ ] T139 [P] [US8] Add transfer navigation authorization tests in `frontend/src/navigation.test.js`
- [ ] T140 [US8] Add transfer destination metadata in `frontend/src/navigation.js` and route it in `frontend/src/AppShell.jsx`
- [ ] T141 [P] [US8] Add RED transfer adapter contract tests in `frontend/src/features/transfers/transferApi.test.js`
- [ ] T142 [US8] Implement generated-client-derived transfer adapter in `frontend/src/features/transfers/transferApi.js`
- [ ] T143 [P] [US8] Add RED create/list/detail/transition UI tests in `frontend/src/features/transfers/TransfersPage.test.jsx`
- [ ] T144 [US8] Implement accessible transfer request form in `frontend/src/features/transfers/TransferForm.jsx`
- [ ] T145 [US8] Implement scoped transfer list/loading/error/empty/conflict states in `frontend/src/features/transfers/TransfersPage.jsx`
- [ ] T146 [US8] Implement detail and permitted transition controls in `frontend/src/features/transfers/TransferDetail.jsx`
- [ ] T147 [US8] Add mobile-responsive transfer/context/dashboard styling in `frontend/src/style.css`
- [ ] T148 [P] [US8] Add desktop/mobile full journey in `e2e/phase5-network-journey.spec.js`
- [ ] T149 [P] [US8] Add stale-context response discrimination journey in `e2e/phase5-context-discrimination.spec.js`
- [ ] T150 [P] [US8] Add exact conflict/idempotency browser assertions in `e2e/phase5-transfer-concurrency.spec.js`
- [ ] T151 [US8] Create clean Compose lifecycle/port ownership/credential export harness in `scripts/phase5/container-network-journey.sh`
- [ ] T152 [US8] Run desktop/mobile browser journey through `scripts/phase5/container-network-journey.sh` with exact expected non-2xx counts and no persistent media artifacts
- [ ] T153 [US8] Restart backend and prove migration idempotency plus durable synthetic transfer state in `scripts/phase5/container-network-journey.sh`
- [ ] T154 [US8] Stop/restart PostgreSQL and prove truthful readiness/liveness plus same-process recovery in `scripts/phase5/container-network-journey.sh`

**Checkpoint:** US8 proves the complete portfolio journey from clean disposable state.

## Phase 11 — Polish, canonical acceptance, traceability, and formal stop

- [ ] T155 Create guarded Phase 4-shaped PostgreSQL rehearsal in `scripts/phase5/migrate-phase4-shaped-postgres.sh`
- [ ] T156 Create repeatable transfer race harness with no sleeps/suppression in `scripts/phase5/test-transfer-concurrency.sh`
- [ ] T157 Create fail-fast canonical  Phase 5 acceptance stages in `scripts/phase5/acceptance.sh`
- [ ] T158 Add capability-negative proof so missing Docker/PostgreSQL/JDK/Node exits `2 BLOCKED` in `scripts/phase5/acceptance.sh`
- [ ] T159 Add cleanup proof for all Phase 5 containers/networks/volumes/databases/temp evidence in `scripts/phase5/acceptance.sh`
- [ ] T160 Run public-artifact scan against exact tracked/untracked candidate paths with `scripts/phase5/check-public-artifacts.sh`
- [ ] T161 [P] Update architecture/hierarchy/transfer boundaries in `docs/pdr.md` by additive Phase 5 section only
- [ ] T162 [P] Update local review and migration instructions in `docs/runbook.md`
- [ ] T163 [P] Update implementation truth and known limits in `docs/implementation-status.md`
- [ ] T164 [P] Add Phase 5 architecture diagram and data-flow explanation in `docs/architecture/phase5-enterprise-network.md`
- [ ] T165 [P] Add Phase 5 threat model for hierarchy, patient grants, transfer races, and idempotency in `docs/security/threat-model-phase5.md`
- [ ] T166 Complete FR-001..035 and SC-001..014 mapping in `specs/005-enterprise-hospital-network-operations/traceability.md` and `docs/evidence/phase5-verification.md`
- [ ] T167 Run `git diff --check`, changed-path allowlist, generated/runtime artifact rejection, and secret/claim scan before any publication decision
- [ ] T168 Run `scripts/phase5/acceptance.sh` fresh and record every stage, exit code, exact test totals, and cleanup result
- [ ] T169 Re-read every acceptance criterion against fresh evidence and assign terminal `PASS | PARTIAL | BLOCKED` in ignored `artifacts/phase5/execution-report.md`
- [ ] T170 Finalize the honest terminal verdict in ignored `artifacts/phase5/execution-report.md` and stop with the worktree intact; do not commit, push, open PR, merge, deploy, release, or announce readiness without separate owner authorization

## Dependencies and execution order

```text
Phase 1 Governance/Baseline
  -> Phase 2 Foundation/Migrations
    -> US1 Hierarchy
      -> US2 Acting Context
        -> US3 Network Patient Identity
          -> US4 Transfer MVP
            -> US5 Capacity
            -> US6 Command Center
            -> US7 Audit/Security
              -> US8 Browser Journey
                -> Phase 11 Canonical Acceptance/Stop
```

- V5/V6/V7 migrations, auth/JWT, OpenAPI generation, shared generated client, and canonical acceptance are serialized.
- After US4 is green, US5, US6, and portions of US7 may run in parallel only when files do not overlap.
- Frontend work begins only after corresponding backend/OpenAPI contracts are green.
- No user story bypasses Foundation.

## Parallel opportunities

- Baseline inventories T006–T008.
- DTO/API RED tests within a story before shared implementation.
- Backend-focused tests and frontend component RED tests after contracts stabilize.
- Documentation T161–T165 after behavior is accepted.
- US5 capacity and US7 telemetry tests may proceed alongside isolated US6 DTO tests, but shared reporting classes are serialized.

## Implementation strategy

### MVP first

The smallest complete enterprise demonstration is Phases 1–2 plus US1–US4. Stop and validate after T103. This produces hierarchy, authority, one patient identity, and a complete safe inter-hospital transfer. Capacity, command-center polish, broader audit evidence, and full browser acceptance follow only after that MVP is independently green.

### Checkpoint discipline

After every numbered task:

1. Record changed paths.
2. Record the RED command and expected failure when behavior is added.
3. Record GREEN command, exit code, exact pass/fail totals.
4. Run the smallest relevant regression gate.
5. Mark `PASS | PARTIAL | BLOCKED` honestly.
6. Do not weaken a test, migration guard, or isolation rule to proceed.

## Planned final acceptance stages

1. capability-negative-proof
2. public-artifact-scan
3. Phase 4 baseline regression
4. V5–V7 empty migration and restart
5. Phase 4-shaped migration rehearsal
6. backend unit/API/security suite
7. PostgreSQL hierarchy/patient/transfer concurrency
8. frontend tests/typecheck/build
9. OpenAPI/client drift
10. Compose health
11. desktop/mobile network journey
12. restart/dependency recovery
13. audit/log/metrics leakage matrix
14. docs/traceability
15. disposable cleanup proof

**Final success definition:** All 15 planned stages exit `0`, all SC-001..SC-014 map to fresh evidence, and no forbidden side effect or unsupported claim occurred. Otherwise Phase 5 is not complete.
