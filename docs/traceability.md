# Requirements Traceability — Training/Portfolio Milestone

Expands the PDR traceability matrices (`docs/plan1.md` §6, `docs/plan2.md` §4) with the actual tasks, tests, and runtime evidence behind each requirement, and carries the **Plan 3 formal acceptance map** (every `docs/plan3.md` §5 Definition-of-Done row mapped to exact source, tests, browser/smoke/runtime evidence, and documentation). Scope statement: this is the **Training/Portfolio** milestone of an educational, non-clinical project — Phase 3 demonstrates **one synthetic organization with three synthetic branches** and nothing here is a production-readiness, capacity, SLA, multi-tenancy, clinical, or security-certification claim. Pilot-gated rows are explicitly marked *not done*.

> **Historical note on counts:** the Phase 1 sections below were written at the Phase 1 acceptance review and quote the suite counts of that time (32 backend / 61 frontend). The combined suite has since grown through the care-operations and multi-branch work; the current fresh counts are recorded in the verification-commands section and in the Plan 3 acceptance map below. Older rows describe what each milestone evidenced, not the current totals.

## Requirement-by-requirement traceability

### 1. Realistic modular hospital workflow

- **Tasks:** 1, 3–10 — commit map: Task 1 `53d9e62`, Task 3 `d4f0516`, Task 4 `e3bbc66`, Task 5 `fbfa290`, Task 6 `15f20de` (#1), Task 7 `33f2839` (#2), Task 8 `40323b3` (#3), Task 9 `b49e549` (#4), Task 10 `968d9c9` (#5).
- **Demonstrated journey:** login → register → search/detail → edit → schedule → audit (`docs/patient-journey.md`).
- **Tests:** backend `PatientJourneyApiTest` (24 tests in the current suite — e.g. `receptionistCreatesThenSearchesPatientByFullName`, `verifiedReferencesCreateAppointmentWithStableContract`); frontend `PatientsPage.test.jsx` (registration/edit/search/selection states), `AppointmentsPage.test.jsx` (scheduling flow), `AppShell.test.jsx` (navigation shell integration).
- **Runtime evidence:** `scripts/smoke-patient-journey.sh` performs the branch-aware journey over the API and fails non-zero on any contract deviation; the ADMIN Audit screen displays the mutations the journey produced.

### 2. Java 21 + Spring Boot 3 modular monolith

- **Tasks:** 1, 3, 4, 9 (commits `53d9e62`, `d4f0516`, `e3bbc66`, `b49e549`).
- **Architecture boundaries:** `docs/architecture/patient-journey.md`, extended by `docs/architecture/care-operations.md` and `docs/architecture/multi-branch-operations.md` — controllers are narrow HTTP/DTO mappers; workflow/reference/audit rules live in services; shared error mapping lives only in `GlobalExceptionHandler`; `ArchitectureSmokeTest` guards the boundary.
- **Tests (fresh):** `mvn test` from `backend/` — 162 tests, 0 failures (see verification commands).

### 3. REST/JSON API

- **Tasks:** 1, 3, 4, 6–10 (plan1), 1–11 (plan3).
- **Contract truth:** `docs/api.md` (endpoints, role matrix, error contract, demo-data flag).
- **Tests:** DTO field-set pins — `patientResponsesExposeStableDtoFieldsWithoutPersistenceInternals`, `appointmentDetailAndListExposeDtoContract`, `staffListExposesDtoContractForProfessionalSelection`, `bedCreateDerivesOwnershipAndReturnsOnlyNormalizedDtoFields`; error contract — `blankRequiredPatientValuesReturn400`-family validation pins, `malformedUuidPathReturnsClientErrorNotServerError`, lifecycle and scheduling `409` pins across `CareOperationsApiTest`/`PatientJourneyApiTest`/`MultiBranchOperationsApiTest`.
- **Runtime evidence:** the smoke script asserts exact response fields (`accessToken`/`roles`/`assignments`/`actingContext` at login, `id` fields, arrays, the typed dashboard summaries, audit context fields) on every step.

### 4. H2 local development

- **Tasks:** 1, 11 (commit `016b56a`, #6).
- **Behavior:** file-backed H2 under `backend/data/` for local review; all integration tests run against isolated in-memory H2 (`jdbc:h2:mem:…`), never the file store; the H2 console is disabled (`application.yml`: `h2.console.enabled: false`). The e2e runner and smoke use disposable isolated H2 only (`docs/runbook.md`).
- **Tests:** `DemoDataInitializerTest` (13 tests in the current suite: flag-on seeding, referential integrity, idempotency, default-off gate).

### 5. PostgreSQL production-like profile — **Pilot horizon, not done**

- **Status:** the `postgres` Spring profile exists with runtime-provided `DB_URL`/`DB_USER`/`DB_PASSWORD`, but migration rehearsal and restore evidence are Pilot-phase work requiring owner approval. No PostgreSQL run has been performed or evidenced in this milestone.

### 6. React + Vite frontend

- **Tasks:** 2, 5–10 (plan1), 5, 6, 10, 13 (plan3).
- **Tests (fresh):** `cd frontend && npm test` — 237 tests across 15 files; `npm run build` — production bundle exit 0.
- **Runtime evidence:** dev/preview servers on port `5502` proxy `/api` to loopback `5501` (`vite.config.js`); the Playwright browser journeys (`npm run test:e2e`, pinned `@playwright/test` 1.61.0) prove the shell, context switching, responsive layouts, keyboard operation, visible focus, and denial states in a real Chromium at desktop 1280×720 and mobile 375×812 against the dedicated review pair (`127.0.0.1:5591`/`5592`), with the screenshot set in `docs/evidence/phase3/` and its inspection record.

### 7. JWT bearer token + role model

- **Enforcement:** `SecurityConfig` — public only `POST /api/auth/login` and `/actuator/health`; explicit family rules; method-level write rules; ADMIN-only catch-all for unmatched `/api/**`. Since Phase 3, authority comes from the per-request re-derived acting assignment (`JwtFilter` → `ActingContextService.reload`), never from token role claims — `alteredRoleClaimsCannotWidenOrNarrowServerDerivedAuthority`, `tamperedScopeClaimLeavesRequestsUnauthenticated`, `contextHeadersAndBodyTamperingCarryNoAuthority`.
- **Tests:** `SecurityAuthorizationTest` (38); frontend `authorization.test.js` (full role × action matrix, deny-by-default).
- **Runtime evidence:** `401`/`403` handling is pinned end-to-end (component tests + browser journeys); the smoke fails on any non-2xx contract deviation.

### 8. Application-level audit for mutations

- **Behavior:** every successful mutation writes exactly one `AuditEvent` atomically with the mutation, now carrying the acting assignment, role, scope, organization, branch, optional department, and the bounded correlation id; failed operations create none. `GET /api/audit` is ADMIN-only and scope-aware with conjunctive filters; the `AuditPage` renders only evidence columns — never `details`, metadata, or tokens.
- **Tests:** `adminObservesCreateAuditEventTiedToCreatedPatient`, `failedAppointmentValidationCreatesNeitherAppointmentNorAudit`, `careOperationsAuditSweepPinsExactlyOneEventCanonicalDetailsAndNoSensitiveLeakage`, `sensitiveCommandsEchoAndPersistOneBoundedCorrelationId`, `auditEvidenceCarriesBranchContextAndTheReadIsScopeAwareAndFilterable`, `contextSwitchEmitsExactlyOneSafeAuditEventAndFailuresEmitNone`; frontend `AuditPage.test.jsx`.
- **Runtime evidence:** smoke step `audit_context` requires the created patient's event under the branch/resourceType filters with full acting context.

### 9. Docker intentionally postponed

- **Evidence of absence:** no Dockerfile, no compose files, no Docker work anywhere in the plans. Scope gate held.

### 10. Educational, non-certified boundary

- **Evidence:** this document, the prominent boundary statements in `README.md`, `docs/patient-journey.md`, `docs/care-operations.md`, `docs/multi-branch-operations.md`, the architecture documents, `docs/runbook.md`, and the "explicitly not done" list in `docs/implementation-status.md`. No production-readiness, multi-tenancy, Pilot, or clinical claim exists in tracked docs.

### 11. Regulatory review, threat modelling, migrations, backups, observability, integrations, deeper testing — **Pilot/Productized horizons, not done**

- **Status:** none of these have been started; they are owner-decision gates (`docs/plan1.md` §9, `docs/plan2.md` §8, `docs/plan3.md` §10) and separately approved work. The milestone stops at the Phase 3 formal acceptance stop gate.

## Plan 2 traceability — care operations (docs/plan2.md §4)

Evidence kinds: **[tests]** automated backend/frontend suites; **[smoke]** the live sequential journey run by `scripts/smoke-patient-journey.sh` against a disposable local backend (supervisor-recorded baseline in `docs/performance.md`); **[docs]** documentation verified line-by-line against source (this task).

### §4.1 — Admission for an existing patient, server-stamped discharge, verified and audited

- **Implementation:** `AdmissionController` / `AdmissionService` / `AdmissionDtos` (backend `admission/`), `frontend/src/features/admissions/admissionApi.js`, `AdmissionsPage.jsx`, `PatientDetailPage.jsx` ("Register admission" entry).
- **[tests]** `CareOperationsApiTest`: `admissionCreatePinsNormalizedDtoContractWithVerifiedPatientReference`, `admissionCreateRejectsUnknownPatientAndMalformedBodiesWithoutPersisting`, `admissionDischargePinsServerStampedTransitionConflictAndAudit` (server-stamped `dischargedAt`, repeat discharge `409`, exactly one UPDATE event), `admissionDeleteRemainsServiceOwnedSafeAndAudited`; frontend `AdmissionsPage.test.jsx`.
- **[smoke]** steps `admission_create` and `admissions_DISCHARGED` (plus the Phase 3 bed-occupancy/transfer/release verifications around them); timed rows in the `docs/performance.md` Task 14 baseline.
- **[docs]** `docs/care-operations.md` step 2; `docs/api.md` admissions section; `docs/architecture/multi-branch-operations.md` §4.1.

### §4.2 — Emergency visit with `1–5` demo label and legal transitions

- **Implementation:** `EmergencyVisitController` / `EmergencyVisitService` / `EmergencyVisitDtos` (backend `emergency/`), `frontend/src/features/emergency/emergencyApi.js`, `EmergencyVisitsPage.jsx`.
- **[tests]** `CareOperationsApiTest`: `emergencyVisitCreatePinsNormalizedDtoContractWithVerifiedPatientReference`, `emergencyVisitCreateRejectsUnknownPatientAndInvalidTriageWithoutPersisting` (non-`1–5` → `400`), `emergencyVisitTransitionsPinLifecycleConflictsAndAudit` (`WAITING → IN_TREATMENT → CLOSED`; `CLOSED` terminal → `409`), `emergencyVisitDeleteRemainsServiceOwnedSafeAndAudited`; frontend `EmergencyVisitsPage.test.jsx`.
- **[smoke]** steps `emergency_create`, `emergency-visits_IN_TREATMENT`, `emergency-visits_CLOSED` — the only legal path asserted; timed rows in the Task 14 baseline.
- **[docs]** `docs/care-operations.md` step 3; `docs/api.md` emergency-visits section (neutral demo label stated verbatim).

### §4.3 — Invoice lifecycle, unique number, ADMIN/BILLING, no payment integration

- **Implementation:** `InvoiceController` / `InvoiceService` / `InvoiceDtos` / `Invoice` (DB unique constraint `uk_invoices_invoice_number`), `frontend/src/features/billing/invoiceApi.js`, `InvoicesPage.jsx` (on-page simulation statement).
- **[tests]** `CareOperationsApiTest`: `invoiceCreatePinsNormalizedDtoContractWithVerifiedPatientReference` (canonical `toPlainString()` storage), `invoiceCreateRejectsInvalidReferencesAmountsAndDuplicatesWithoutPersisting`, `invoiceTransitionsPinLifecycleConflictsAndAudit`, `invoiceDeleteRemainsServiceOwnedSafeAndAudited`; `SecurityAuthorizationTest.invoiceWritesAdmitAdminAndBillingWhileClinicalRolesPersistNothing`; frontend `InvoicesPage.test.jsx`.
- **[smoke]** steps `invoice_create` (unique `SMOKE-INV-…` number, `DRAFT`), `invoices_ISSUED`, `invoices_PAID`; timed rows in the Task 14 baseline.
- **[docs]** `docs/care-operations.md` step 4; `docs/api.md` invoices section; financial-simulation boundary stated in all touched docs.

### §4.4 — Status-aware dashboard with labels and smoke-asserted contract

- **Status:** superseded in Phase 3 by the typed branch/network command centers; the historical eleven-flat-key contract was replaced by `DashboardDtos.BranchSummary`/`NetworkSummary` with the deprecated alias pinned to the branch shape (`DashboardApiTest.legacyAliasIsExactlyTheBranchSummaryAndNeverWholeTable`). Original evidence: `DashboardService` eleven-key aggregation; `DemoDataInitializerTest.dashboardAggregatesReflectExactFixtureComposition` (now `dashboardsCountTheCohortExactlyWithNonzeroCoveragePerBranch`); smoke `dashboard` step (now `branch_dashboard`/`network_dashboard`).

### §4.5 — Role-aware navigation and enforced RBAC agreement (incl. BILLING isolation)

- **Implementation:** `frontend/src/navigation.js`, `authorization.js`, `AppShell.jsx`; server authority in `SecurityConfig` plus the acting-context derivation of Phase 3.
- **[tests]** `SecurityAuthorizationTest` family matrices (including `admissionEmergencyAndBedFamiliesAllowFourRolesWhileBillingIsIsolated` coverage via the per-family tests); frontend `navigation.test.js`, `authorization.test.js`, `AppShell.test.jsx`.
- **[smoke]** single-ADMIN journey; RBAC evidence remains the test suites' job by design.
- **[docs]** `docs/care-operations.md` role-boundary table; `docs/api.md` enforced role matrix.

### §4.6 — Exactly-one audit per successful mutation, none on failure, ADMIN screen

- **Implementation:** `AuditService` called by every workflow service inside their transactions; `frontend/src/features/audit/AuditPage.jsx`; extended in Phase 3 with acting-context columns, `CorrelationIdFilter`, and the scope-aware `AuditController`.
- **[tests]** `careOperationsAuditSweepPinsExactlyOneEventCanonicalDetailsAndNoSensitiveLeakage`, `sensitiveCommandsEchoAndPersistOneBoundedCorrelationId`, per-family failure pins; frontend `AuditPage.test.jsx`.
- **[docs]** `docs/care-operations.md` step 6; `docs/architecture/multi-branch-operations.md` §6.

### §4.7 — Coherent opt-in fixtures exercising the new flows

- **Implementation:** `DemoDataInitializer` — superseded in Phase 3 by the three-branch cohort (see Plan 3 row 11 below).
- **[tests]** `DemoDataInitializerTest` (13 tests in the current suite).

### §4.8 — Suites, build, and smoke pass; dated baseline without capacity claim

- **[tests]** fresh worker run at the documentation revision: backend 162 tests, frontend 237 tests, build exit 0 — see verification commands.
- **[smoke]** supervisor-recorded baselines in `docs/performance.md` (Task 14 branch-aware baseline is the current reference; single-workstation regression tripwire; explicitly no capacity claim).
- **[acceptance]** Plan 2 stop gate accepted at snapshot `b9df613` (history recorded in `docs/implementation-status.md`).

### §4.9 — Documentation describes only verified behavior; `git diff --check` clean

- **Implementation:** Plan 2 Task 10 (history) and Plan 3 Task 15 (this change) — `docs/care-operations.md`, architecture documents, `docs/api.md`, `docs/traceability.md`, `docs/implementation-status.md`, `docs/patient-journey.md`, `docs/runbook.md`, `README.md`.
- **[docs]** every endpoint, DTO field, status value, role, dashboard key, command, and tracked path was checked against source during the tasks; `git diff --check` runs as a gate (see verification commands).

### §4.10 — Non-clinical boundary statement unchanged everywhere

- **[docs]** the Training/Portfolio, synthetic-data, non-clinical, financial-simulation, single-organization, and no-capacity boundaries appear prominently in `README.md`, `docs/care-operations.md`, `docs/multi-branch-operations.md`, the architecture documents, `docs/api.md`, `docs/runbook.md`, `docs/performance.md`, and `docs/implementation-status.md`. No tracked doc claims clinical, payment, Pilot, multi-tenancy, or production capability.

## Plan 3 formal acceptance — multi-branch operations (docs/plan3.md §5)

This is the **formal stop-gate acceptance map**: every Definition-of-Done outcome (rows 1–14) is mapped to exact source, tests, browser/smoke/runtime evidence, and documentation. Row 15 is this map itself, enforced at the documentation revision recorded in the verification-commands section. Evidence kinds: **[source]** implementation paths; **[tests]** automated suites; **[browser]** `npm run test:e2e` journeys and `docs/evidence/phase3/`; **[smoke]** the live isolated run recorded below and the dated baseline in `docs/performance.md`; **[docs]** public documents.

### DoD 1 — One synthetic organization and three synthetic branches with branch-owned departments and unique codes

- **[source]** `backend/.../organization/HospitalOrganization.java`, `Branch.java`, `BranchRepository.java`, `OrganizationService.java`, `OrganizationController.java`, `OrganizationDtos.java`; `department/DepartmentService.java`; `bootstrap/DemoDataInitializer.java`.
- **[tests]** `MultiBranchOperationsApiTest`: `organizationEndpointReturnsTheDtoAllowlistWithOnlyActiveBranchesInDeterministicOrder`, `branchListAndBranchGetExposeAllowlistedDtosInDeterministicOrder`, `branchCreateTrimsInputsReturns201AndTheAllowlistedDto`, `duplicateBranchCodeIsConflict`, `departmentsRequireAVerifiedBranchAndReturnTheAllowlistedDto`, `sameDepartmentCodeIsAllowedAcrossBranchesButRefusedInsideOneBranch`; `DemoDataInitializerTest.threeBranchHierarchyIsSeededUnderStableBusinessKeys`.
- **[smoke]** primary/other branch resolution printed from server data (`DEMO-BR-001`/`DEMO-BR-002`); **[docs]** `docs/multi-branch-operations.md` §1.1, `docs/runbook.md` demo-seed section, `docs/api.md` organization/branches/departments sections.

### DoD 2 — Identity and acting assignment separate; multi-assignment switching; disabled/foreign fail closed

- **[source]** `auth/UserAccount.java`, `ActingAssignment.java`, `ActingAssignmentRepository.java`, `ActingContext.java`, `ActingContextService.java`, `BranchAccessService.java`, `JwtService.java`, `JwtFilter.java`, `AuthController.java`, `DevAdminInitializer.java`.
- **[tests]** `SecurityAuthorizationTest`: `multiAssignmentUserSwitchesAssignmentsAndEachReplacementTokenCarriesExactlyTheTargetRole`, `foreignAndUnknownAssignmentSwitchesAreForbiddenWithoutAuditAndWithoutEnumeration`, `disabledAssignmentInvalidatesItsTokenAndRefusesSwitching`, `deletedAssignmentInvalidatesItsTokenImmediately`, `disabledAccountFailsClosedOnEveryPath`, `alteredRoleClaimsCannotWidenOrNarrowServerDerivedAuthority`, `legacyRoleUnionGrantsNothingWithoutAnAssignmentAndNeverAppearsAsAuthority`, `loginAndSwitchListOnlyCurrentlyValidAssignments`, `organizationScopeSwitchesOnlyBetweenActiveBranchesInsideItsOrganization`, `inactiveOrDeletedSelectedBranchFailsClosed`, `tampered*Claim*` family; `MultiBranchOperationsApiTest.loginResponseCarriesTheActingAssignmentAllowlistWithOneSelectedRole`; `DevAdminInitializerTest.adminProvisioningCreatesOrganizationScopeAssignmentWhenHierarchyExists`.
- **[browser]** desktop+mobile journeys assert the shell identity/role/branch line and switch to `Demo North Branch` through the selector (replacement token); **[smoke]** `context_select` and `context_switch` steps require the server-issued session shape; **[docs]** `docs/api.md` base/auth section, `docs/patient-journey.md` step 1, `docs/multi-branch-operations.md` §1.2, `docs/architecture/multi-branch-operations.md` §2.

### DoD 3 — Every migrated branch-scoped operation rejects cross-branch access server-side; legacy rows do not leak

- **[source]** branch-parameterized repository methods and context-seamed services: `patient/PatientRepository.java` + `PatientService.java`, `staff/StaffMemberRepository.java`, `appointment/AppointmentRepository.java` + `AppointmentService.java`, `admission/AdmissionRepository.java` + `AdmissionService.java`, `emergency/EmergencyVisitRepository.java` + `EmergencyVisitService.java`, `billing/InvoiceRepository.java` + `InvoiceService.java`, `bed/BedRepository.java` + `BedService.java`.
- **[tests]** `MultiBranchOperationsApiTest`: `admissionBedCommandsResolveReferencesOnlyInsideTheActingBranch`, `admissionListDetailAndCommandsAreScopedToTheActingBranch`, `emergencyVisitsAreOwnedByTheActingBranchAndEveryReadAndCommandIsBranchScoped`, `invoicesAreOwnedByTheActingBranchAndEveryReadAndCommandIsBranchScoped`, `workflowRecordsAreOwnedByTheActingBranchAndEveryWorkflowReadIsBranchScoped`, `crossBranchReferencesAreRefusedWithoutAuditWhileGlobalUniquenessStaysGlobal`, `unassignedLegacyWorkflowRowsStayInvisibleAndUntouchableThroughBranchScopedEndpoints`, `unassignedLegacyDepartmentsAreInvisibleAndUntouchableThroughTheNormalizedContract`, `bedReadsAreBranchScopedAndDuplicateKeysConflictOnlyInsideOneBranch`, `contextHeadersCarryNoAuthorityAndReadsFollowOnlyTheServerDerivedAssignment`; `DashboardApiTest.branchSummaryIsolatesBranchesAndLegacyRows`.
- **[browser]** cross-branch patient search miss and beds/admissions isolation on both viewports; **[smoke]** the seven `isolation_*` steps plus `isolation_audit` must all prove absence from the other branch; **[docs]** `docs/multi-branch-operations.md` §1.3/§3, `docs/architecture/multi-branch-operations.md` §3, `docs/api.md` error contract.

### DoD 4 — Branch selector shows only authorized contexts, switches via a newly issued context-bound token, and displays current branch and role

- **[source]** `frontend/src/features/branches/BranchSelector.jsx`, `actingContextApi.js`, `frontend/src/AppShell.jsx`, `frontend/src/auth.js`, `frontend/src/api.js` (`parseActingSessionPayload`).
- **[tests]** frontend `BranchSelector.test.jsx`, `AppShell.test.jsx`, `multiBranchContracts.test.js`, `auth`-session validation tests; backend pins in DoD 2 rows.
- **[browser]** both journeys: selector options exactly the three server-issued branch pairs, `.whoami-context`/`.whoami-roles` assertions after login and after the north-branch switch with no stale content; **[smoke]** `context_select`/`context_switch`; **[docs]** `docs/patient-journey.md` step 1, `docs/architecture/multi-branch-operations.md` §7.

### DoD 5 — Receptionist completes a branch-scoped patient → availability → appointment journey; outside-availability and overlapping appointments return `409` without partial writes or success audit events

- **[source]** `staff/StaffAvailability*.java`, `appointment/AppointmentService.java` (containment + overlap, pessimistic interval lock), `appointment/AppointmentDtos.java` (`durationMinutes` 5–480).
- **[tests]** `PatientJourneyApiTest`: `appointmentOverlapIsRefusedInBothDirectionsWhileAdjacencyIsAllowed`, `appointmentDurationBoundariesAreValidatedOnTheServer`, `onlyTheAlreadyDefinedCancelledStatusIsExcludedFromConflictDetection`, `verifiedReferencesCreateAppointmentWithStableContract`, `failedAppointmentValidationCreatesNeitherAppointmentNorAudit`; `MultiBranchOperationsApiTest`: `schedulingRequiresContainingAvailabilityAndRejectsOverlapAndOutsideSlots`, `sameTimestampRemainsIndependentAcrossProfessionalsAndBranches`, `legacyAppointmentsWithoutComputedWindowsStayOutOfConflictCandidates`, `concurrentSameTimeAppointmentCreatesProduceExactlyOneWinnerAndOneConflict`; `SecurityAuthorizationTest.availabilityWritesStayAdminHrOnlyWhileTheReadKeepsTheStaffFamilyRules`; frontend `AppointmentsPage.test.jsx` conflict states.
- **[browser]** intentional overlap and outside-availability submissions each return `409` with the inline alert, zero field loss, and an unchanged table; **[smoke]** `appointment_conflict_409` is a required failure step; **[docs]** `docs/api.md` appointments/availability sections, `docs/patient-journey.md` step 6, `docs/multi-branch-operations.md` §1.4, `docs/architecture/multi-branch-operations.md` §4.2.

### DoD 6 — Beds use normalized DTO/service contracts and legal lifecycle states; duplicate identity, invalid transitions, and unavailable-bed assignment return `409`

- **[source]** `bed/Bed.java` (transition map, `@Version`), `BedRepository.java`, `BedService.java`, `BedDtos.java`, `BedController.java`; `frontend/src/features/beds/*`.
- **[tests]** `MultiBranchOperationsApiTest`: `bedLifecycleUsesTheNormalizedDtoAndRecordsExactlyOneAuditEventPerMutation`, `bedReadsAreBranchScopedAndDuplicateKeysConflictOnlyInsideOneBranch`, `occupiedBedsRefuseClientTransitionsAndDeletionWith409WithoutAuditEvents`, `concurrentBedMutationsProduceExactlyOneWinnerAndOnlySafeConflicts`; `CareOperationsApiTest.bedCreateDerivesOwnershipAndReturnsOnlyNormalizedDtoFields`, `admissionBedReferencesRejectUnknownAndUnavailableTargetsWithoutPersisting`; frontend `BedsPage.test.jsx`.
- **[browser]** beds table shows all four statuses as text (never color-only) per branch; **[smoke]** `bed_create_a`/`bed_create_b` and the occupancy verification steps; **[docs]** `docs/api.md` beds section, `docs/care-operations.md` step 2, `docs/multi-branch-operations.md` §1.5.

### DoD 7 — Admission with bed assignment, transfer, and discharge/release are atomic and audited exactly once per successful command

- **[source]** `admission/AdmissionService.java` (`occupyAndAssign`, transfer, `releaseAssignment`), `AdmissionBedAssignment(Repository).java`, `bed/Bed.java` (`markOccupiedByAdmission`, `releaseByAdmission`).
- **[tests]** `CareOperationsApiTest`: `admissionBedLifecyclePinsAssignTransferReleaseAndRollback`, `admissionBedReferencesRejectUnknownAndUnavailableTargetsWithoutPersisting`, `admissionDischargePinsServerStampedTransitionConflictAndAudit`, `careOperationsAuditSweepPinsExactlyOneEventCanonicalDetailsAndNoSensitiveLeakage`; `MultiBranchOperationsApiTest`: `concurrentAdmissionsClaimingOneBedProduceExactlyOneWinner`, `admissionListDetailAndCommandsAreScopedToTheActingBranch`.
- **[browser]** the mobile journey drives register-without-bed → assign → discharge-releases-bed → transfer-into-released-bed and commits the evidence screenshot; **[smoke]** `admission_bed_transfer` plus the three bed-state verification steps and `bed_discharge_release_verify`; **[docs]** `docs/api.md` admissions section, `docs/care-operations.md` step 2, `docs/architecture/multi-branch-operations.md` §4.1.

### DoD 8 — Emergency visits and simulated invoices are branch-scoped without changing their non-clinical and no-payment lifecycle boundaries

- **[source]** `emergency/EmergencyVisitService.java`, `billing/InvoiceService.java` and their repositories/DTOs.
- **[tests]** `MultiBranchOperationsApiTest`: `emergencyVisitsAreOwnedByTheActingBranchAndEveryReadAndCommandIsBranchScoped`, `invoicesAreOwnedByTheActingBranchAndEveryReadAndCommandIsBranchScoped`; `CareOperationsApiTest` lifecycle pins (see §4.2/§4.3 above) unchanged.
- **[browser]** branch isolation on screens; **[smoke]** the emergency/invoice steps and their isolation twins; **[docs]** `docs/api.md` emergency/invoice sections, `docs/care-operations.md` steps 3–4, `docs/multi-branch-operations.md` §3.

### DoD 9 — Branch dashboard contains only the active branch; network dashboard limited to organization-scoped ADMIN with typed per-branch comparisons

- **[source]** `reporting/DashboardService.java`, `DashboardController.java`, `DashboardDtos.java`; branch-restricted grouped repository count queries.
- **[tests]** `DashboardApiTest`: `branchSummaryExposesExactContractKeysWithAllZeroCountsOnFreshData`, `branchSummaryAggregatesExactScopedBucketsSideEffectFree`, `branchSummaryIsolatesBranchesAndLegacyRows`, `networkReturnsDeterministicScopedSummariesForOrganizationAdmins`, `networkDeniesEveryNonOrganizationAdminContext`, `legacyAliasIsExactlyTheBranchSummaryAndNeverWholeTable`, `todayBoundaryIsOwnedByTheServerClock`; `MultiBranchOperationsApiTest.dashboardAliasIsABranchScopedSummaryAndNeverWholeTable`, `networkDashboardAuthorizesOnlyOrganizationScopedAdmins`; frontend `DashboardPage.test.jsx`.
- **[browser]** command center asserts the branch line, network comparison listing all three branches, and drill-downs on both viewports; **[smoke]** `branch_dashboard`, `network_dashboard`, `branch_dashboard_other` (identity/total match required); **[docs]** `docs/api.md` dashboard section, `docs/care-operations.md` step 5, `docs/multi-branch-operations.md` §1.6, `docs/architecture/multi-branch-operations.md` §5.

### DoD 10 — Audit evidence identifies actor, assignment, role, branch, optional department, action/resource, correlation ID, and timestamp without secrets or request bodies

- **[source]** `audit/AuditEvent.java`, `AuditService.java`, `AuditController.java`, `CorrelationIdFilter.java`; `SecurityConfig` (correlation filter ordering).
- **[tests]** `MultiBranchOperationsApiTest.auditEvidenceCarriesBranchContextAndTheReadIsScopeAwareAndFilterable`; `SecurityAuthorizationTest.contextSwitchEmitsExactlyOneSafeAuditEventAndFailuresEmitNone`, `assertBoundedCorrelation`; `CareOperationsApiTest.sensitiveCommandsEchoAndPersistOneBoundedCorrelationId`, `careOperationsAuditSweepPinsExactlyOneEventCanonicalDetailsAndNoSensitiveLeakage`; `DemoDataInitializerTest.seededActionsProduceContextAwareSystemActorAuditEvents`; frontend `AuditPage.test.jsx`.
- **[browser]** audit table shows system-actor branch-attributed rows; browser assertions prove that credential material never appears in the page body; **[smoke]** `audit_context` requires the created patient's event under branch/resourceType filters with full acting context; **[docs]** `docs/api.md` audit section, `docs/care-operations.md` step 6, `docs/multi-branch-operations.md` §1.7, `docs/architecture/multi-branch-operations.md` §6.

### DoD 11 — Opt-in fixture cohort idempotent and referentially coherent across three branches; second seed creates zero duplicates; no branch-owned fixture has a null/dangling branch

- **[source]** `bootstrap/DemoDataInitializer.java`, `auth/DevAdminInitializer.java`.
- **[tests]** `DemoDataInitializerTest`: `repeatedInitializationInsertsZeroRecordsAndZeroEvents`, `everyBranchOwnedDemoRowHasValidSameBranchOwnershipAndReferences`, `noBedAdmissionContradictionExists`, `bedsCoverAllOperationalStatusesAcrossBranches`, `careOperationFixturesCoverEveryLifecycleStatusAcrossBranches`, `datedAvailabilityIsSameBranchConflictFreeAndCoversEveryAppointment`, `cohortActingAssignmentsAreEnabledAndDeterministicallyBound`, `dashboardsCountTheCohortExactlyWithNonzeroCoveragePerBranch`, `threeBranchHierarchyIsSeededUnderStableBusinessKeys`, `seededActionsProduceContextAwareSystemActorAuditEvents`, `unknownNullBranchDepartmentsStayUntouchedAndRemainUnassigned`, `initializerIsDisabledByDefaultAndNeverTouchesStores`; `DevAdminInitializerTest` (13).
- **[browser]** both journeys run against the freshly seeded cohort; **[smoke]** the isolated run boots with `MEDICORE_DEMO_SEED=true` on a fresh in-memory store; **[docs]** `docs/runbook.md` demo-seed section (composition, 54 first-run events), `docs/api.md` demo-data section, `docs/multi-branch-operations.md` §1.8.

### DoD 12 — Frontend suites and production build pass; automated browser journeys prove branch switching, responsive layouts, keyboard operation, visible focus, and denial/error states at defined mobile and desktop viewports

- **[source]** `frontend/src/**` (shell, selector, feature pages, `style.css` focus rules), `frontend/playwright.config.js`, `frontend/e2e/multi-branch-journey.spec.js`.
- **[tests]** fresh run: `npm test` — 237 tests across 15 files, 0 failures; `npm run build` — exit 0.
- **[browser]** fresh `npm run test:e2e` — 2/2 journeys passed (desktop 1280×720, mobile 375×812) with per-journey assertions for zero console/page errors, no horizontal overflow, visible keyboard focus, accessible control names, predictable focus return, denial messaging, and stale-branch absence; the three-image set (`docs/evidence/phase3/`) is copied only after every assertion of both viewport runs passes, and its README records the direct inspection performed at Task 13; **[docs]** `docs/runbook.md` browser-evidence section, `docs/multi-branch-operations.md` §2.

### DoD 13 — Extended multi-branch smoke journey passes against an isolated loopback H2 backend; dated single-workstation baseline recorded as a regression tripwire only

- **[source]** `scripts/smoke-patient-journey.sh` (36 timed steps including `context_select`, `appointment_conflict_409`, bed transfer verifications, `network_dashboard`, `audit_context`, seven `isolation_*` steps).
- **[tests]** `bash -n` syntax gate; full suites above.
- **[smoke]** fresh isolated run recorded below (process-local random credentials, loopback-only, in-memory H2, cleanup verified); dated repeated baseline in `docs/performance.md` (Task 14; explicitly not an SLA/capacity claim); **[docs]** `docs/runbook.md` smoke section, `docs/performance.md`.

### DoD 14 — API, architecture, traceability, implementation status, runbook, README, screenshots/evidence, and source agree; public files contain no credentials, private addresses, machine paths, real personal data, or unsupported production/scale claims

- **[docs]** `docs/api.md`, `docs/multi-branch-operations.md`, `docs/architecture/multi-branch-operations.md`, `docs/traceability.md` (this section), `docs/implementation-status.md`, `docs/patient-journey.md`, `docs/care-operations.md`, `docs/runbook.md`, `README.md`, `docs/evidence/phase3/README.md` — verified line-by-line against source during Task 15; the privacy/unsupported-claim audit over the changed paths is recorded in the Task 15 report.
- **[evidence]** screenshots show only `Demo*`/`DEMO-*` synthetic data (documented in `docs/evidence/phase3/README.md`); loopback addresses only; no credentials or tokens anywhere in tracked files.
- **[tests]** `git diff --check` clean; full suites green (see verification commands).

### DoD 15 — Formal acceptance review and stop gate

- This section is the review: every row above maps to fresh evidence recorded at this documentation revision, the acceptance boundary is restated in `docs/implementation-status.md` (stop gate) and `docs/multi-branch-operations.md` §4, and the Task 15 report carries the verdict artifact. Acceptance authorizes only the synthetic Training/Portfolio, single-organization, multi-branch demonstration — no Pilot, later phase, real data, SaaS tenancy, clinical use, payment/insurer integration, PostgreSQL rehearsal, production deployment, or production-readiness/capacity claim (`docs/plan3.md` §10).

## Phase 4 formal acceptance — production-like resilience (specs/004-production-like-resilience)

Training/Portfolio, synthetic-only, non-clinical — same boundary as every section above. Phase 4 adds production-like engineering evidence (Flyway/PostgreSQL, containers, backup/restore, observability, hardened security, deterministic OpenAPI, containerized browser journey) and one canonical fail-fast local acceptance command, `scripts/phase4/acceptance.sh` (13 serial stages, exit 0 only when all pass; missing Docker/PostgreSQL capability exits 2/BLOCKED and stage 0 proves that negatively on every run). The complete FR-001..020 and SC-001..010 evidence map — exact source files, test classes, scripts, and runtime stages — is maintained in [`docs/evidence/phase4-verification.md`](evidence/phase4-verification.md); the fresh terminal totals are recorded in `docs/implementation-status.md` (Phase 4 section). No repository CI exists (no `.github/` at baseline), so the gate is documented as local-only; no CI approval, deployment, or release is authorized or claimed.

Stop gate: the terminal canonical run plus the FR/SC evidence map constitute the Phase 4 acceptance review input for the owner. A PASS authorizes exactly the synthetic Training/Portfolio scope above — nothing else (forward-fix migration policy: no downgrade path is claimed; rollback is by restore from a verified backup archive).

## Verification commands

Fresh record at this documentation revision (clean checkout at baseline `e7c63a0a84f016e6a4f345b9c7971be64eee0c0d`, one workstation, local Temurin 21 + Maven 3.9.11 toolchain; project-local `npm ci` from the committed lockfile only):

```bash
cd backend && mvn -q test
#   exit 0 — 162 tests, 0 failures, 0 errors, 0 skipped
#   (MultiBranchOperationsApiTest 43, SecurityAuthorizationTest 38, PatientJourneyApiTest 24,
#    CareOperationsApiTest 23, DemoDataInitializerTest 13, DevAdminInitializerTest 13,
#    DashboardApiTest 7, ArchitectureSmokeTest 1)
cd ../frontend && npm test
#   exit 0 — 237 tests across 15 files, 0 failures
cd ../frontend && npm run build
#   exit 0 — Vite production bundle
cd ../frontend && npm run test:e2e
#   exit 0 — 2/2 viewport journeys (desktop 1280×720, mobile 375×812) against the disposable
#   loopback review pair 127.0.0.1:5591/5592 with process-local random credentials; disposable
#   H2 store removed in teardown; evidence set regenerated only because both journeys passed
bash -n scripts/smoke-patient-journey.sh
#   exit 0 — syntax gate
git diff --check
#   clean — no whitespace/conflict markers
```

Isolated live smoke (same revision): disposable backend on loopback `127.0.0.1:5577`, in-memory H2 (`jdbc:h2:mem:smoke;MODE=PostgreSQL`, repository-default DDL), `MEDICORE_DEMO_SEED=true`, generated process-local random `HOSPITAL_ADMIN_PASSWORD`/`HOSPITAL_JWT_SECRET` (never printed or persisted):

```bash
BASE_URL=http://127.0.0.1:5577 RUNS=1 \
HOSPITAL_SMOKE_PASSWORD="<process-local random value>" \
./scripts/smoke-patient-journey.sh
# SMOKE RESULT: PASS (runs=1, total_ms=11977), exit 0 — 36/36 steps
# cleanup: backend stopped, port verified closed, temp boot log removed
```

Dated repeated-run baselines for the same script live in `docs/performance.md` (single-workstation regression tripwires; explicitly no production-capacity claim).

## Task → commit index

### Plan 1 (docs/plan1.md §5)

| Task | Objective (plan1.md §5) | Commit | PR |
|---|---|---|---|
| 1 | Characterize the current Patient Journey contracts | `53d9e62` | — |
| 2 | Add frontend test tooling before feature code | `a91fe9f` | — |
| 3 | Normalize Patient Journey API contracts | `d4f0516` | — |
| 4 | Replace raw appointment references with verified relationships | `e3bbc66` | — |
| 5 | Create a lightweight authenticated navigation boundary | `fbfa290` | — |
| 6 | Build patient list and search | `15f20de` | #1 |
| 7 | Build patient registration, detail, and edit | `33f2839` | #2 |
| 8 | Build professional selection and appointment scheduling | `40323b3` | #3 |
| 9 | Align role-aware actions and security regression coverage | `b49e549` | #4 |
| 10 | Add an ADMIN audit evidence screen | `968d9c9` | #5 |
| 11 | Add coherent idempotent synthetic demo data | `016b56a` | #6 |
| 12 | Establish repeatable performance evidence | `6595c03` | #7 |
| 13 | Produce Portfolio and operational evidence | `634961d` | #8 |

### Plan 2 (docs/plan2.md §5)

| Task | Objective (plan2.md §5) | Commit | PR |
|---|---|---|---|
| 1 | Characterize care-operations contracts and authorization | `5c5ae2b` | #15 |
| 2 | Admissions workflow with discharge lifecycle | `49e93e6` | #16 |
| 3 | Emergency-visit workflow with neutral triage label | `ba4d8b4` | #17 |
| 4 | Invoice lifecycle (financial simulation) | `2cd5c6a` | #18 |
| 5 | Status-aware operations dashboard | `c01df1b` | #19 |
| 6 | RBAC matrix alignment | `134b9b6` | #20 |
| 7 | Audit coverage and failure-contract sweep | `6042678` | #21 |
| 8 | Synthetic fixture expansion | `e777136` | #22 |
| 9 | Journey smoke extension and performance re-baseline | `236dc3f` | #23 |
| 10 | Documentation, portfolio evidence, and stop gate | docs change; accepted at snapshot `b9df613` | — |

Publication state: the Plan 2 PRs (#15–#23) are merged into `main` as of 2026-09-10. Task 10 is accepted at snapshot `b9df613`.

### Plan 3 (docs/plan3.md §6)

| Task | Objective (plan3.md §6) | Commit | PR |
|---|---|---|---|
| 1 | Characterize branchless operations and security baseline | `c67c26d` | #26 |
| 2 | Establish organization, branches, branch-owned departments | `c214b00` | #27 |
| 3 | Acting assignments and branch-bound auth context | `48ac07b` | #28 |
| 4 | Scope patient, staff, and appointment workflow by branch | `6e447a8` | #29 |
| 6 | Normalize branch-owned bed inventory lifecycle | `73c3fd2` | #30 |
| 7 | Atomic admission bed assignment and release | `3308f4a` | #31 |
| 5 | Branch-aware acting-context selector and scoped shell | `5691a05` | #32 |
| 8 | Branch scope across emergency and invoice workflows | `c73e7f1` | #33 |
| 9 | Staff availability and appointment conflict prevention | `50c435a` | #34 |
| 10 | Scoped branch and network command centers | `7a1ff44` | #35 |
| 11 | Assignment and branch context in audit evidence | `dc8737c` | #36 |
| 12 | Idempotent three-branch operations cohort | `942bb6b` | #37 |
| 13 | Responsive multi-branch browser evidence | `620d107` | #38 |
| 14 | Multi-branch smoke regression baseline | `aefc861` | #39 |
| 15 | Publish Phase 3 architecture, evidence, and formal stop gate | this docs change | — |

Publication state: the Plan 3 implementation PRs (#26–#39) are merged into `main` (plan merged as #25). Task 15 is accepted at this documentation revision per the Plan 3 acceptance map above; the formal stop gate then closes the roadmap.
