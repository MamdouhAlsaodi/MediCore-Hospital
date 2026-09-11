# Requirements Traceability — Training/Portfolio Milestone

Expands the PDR traceability matrices (`docs/plan1.md` §6, `docs/plan2.md` §4) with the actual tasks, tests, and runtime evidence behind each requirement. Scope statement: this is the **Training/Portfolio** milestone of an educational, non-clinical project — nothing here is a production-readiness or clinical-certification claim. Pilot-gated rows are explicitly marked *not done*.

> **Historical note on counts:** the Phase 1 sections below were written at the Phase 1 acceptance review and quote the suite counts of that time (32 backend / 61 frontend). The combined suite has since grown with the care-operations work; the current fresh counts are recorded in the verification-commands section and in the Plan 2 section below. Older rows describe what each milestone evidenced, not the current totals.

## Requirement-by-requirement traceability

### 1. Realistic modular hospital workflow

- **Tasks:** 1, 3–10 — commit map: Task 1 `53d9e62`, Task 3 `d4f0516`, Task 4 `e3bbc66`, Task 5 `fbfa290`, Task 6 `15f20de` (#1), Task 7 `33f2839` (#2), Task 8 `40323b3` (#3), Task 9 `b49e549` (#4), Task 10 `968d9c9` (#5).
- **Demonstrated journey:** login → register → search/detail → edit → schedule → audit (`docs/patient-journey.md`).
- **Tests:** backend `PatientJourneyApiTest` (18 tests — e.g. `receptionistCreatesThenSearchesPatientByFullName`, `verifiedReferencesCreateAppointmentWithStableContract`); frontend `PatientsPage.test.jsx` (registration/edit/search/selection states), `AppointmentsPage.test.jsx` (scheduling flow), `AppShell.test.jsx` (navigation shell integration).
- **Runtime evidence:** `scripts/smoke-patient-journey.sh` performs the same journey over the API (login → patient create → search → detail → appointment create → list → dashboard) and fails non-zero on any contract deviation; the ADMIN Audit screen displays the mutations the journey produced.

### 2. Java 21 + Spring Boot 3 modular monolith

- **Tasks:** 1, 3, 4, 9 (commits `53d9e62`, `d4f0516`, `e3bbc66`, `b49e549`).
- **Architecture boundaries:** `docs/architecture/patient-journey.md` — controllers are narrow HTTP/DTO mappers; workflow/reference/audit rules live in services (`PatientService`, `AppointmentService`); shared error mapping lives only in `GlobalExceptionHandler`; `ArchitectureSmokeTest` guards the boundary. The care-operations slice extends the same layering (`docs/architecture/care-operations.md`).
- **Tests (at the Phase 1 milestone):** `mvn test` from `backend/` — 32 tests, 0 failures (`PatientJourneyApiTest` 18, `SecurityAuthorizationTest` 9, `DemoDataInitializerTest` 4, `ArchitectureSmokeTest` 1).

### 3. REST/JSON API

- **Tasks:** 1, 3, 4, 6–10.
- **Contract truth:** `docs/api.md` (endpoints, role matrix, error contract, demo-data flag).
- **Tests:** DTO field-set pins — `patientResponsesExposeStableDtoFieldsWithoutPersistenceInternals`, `appointmentDetailAndListExposeDtoContract`, `staffListExposesDtoContractForProfessionalSelection`; error contract — `blankRequiredPatientValuesReturn400`, `invalidPatientDateOfBirthReturns400`, `invalidAppointmentStatusReturns400`, `malformedAppointmentReferencesReturn400`, `malformedUuidPathReturnsClientErrorNotServerError`.
- **Runtime evidence:** the smoke script asserts exact response fields (`accessToken`/`roles`, `id` fields, arrays, dashboard count keys) on every step.

### 4. H2 local development

- **Tasks:** 1, 11 (commit `016b56a`, #6).
- **Behavior:** file-backed H2 under `backend/data/` for local review; all integration tests run against isolated in-memory H2 (`jdbc:h2:mem:…`), never the file store; the H2 console is disabled (`application.yml`: `h2.console.enabled: false`).
- **Tests:** `DemoDataInitializerTest` (4 tests at the Phase 1 milestone: flag-on seeding, referential integrity, idempotency, default-off gate; extended to 8 by the Plan 2 fixture work).

### 5. PostgreSQL production-like profile — **Pilot horizon, not done**

- **Status:** the `postgres` Spring profile exists with runtime-provided `DB_URL`/`DB_USER`/`DB_PASSWORD`, but migration rehearsal and restore evidence are Pilot-phase work requiring owner approval. No PostgreSQL run has been performed or evidenced in this milestone.

### 6. React + Vite frontend

- **Tasks:** 2, 5–10 (commits `a91fe9f`, `fbfa290`, `15f20de` #1, `33f2839` #2, `40323b3` #3, `b49e549` #4, `968d9c9` #5).
- **Tests (at the Phase 1 milestone):** `cd frontend && npm test` — 61 tests across 7 files; `npm run build` — production bundle exit 0. The Plan 2 work extends the suite to 125 tests across 12 files (see below).
- **Runtime evidence:** dev/preview servers on port `5502` proxy `/api` to loopback `5501` (`vite.config.js`); keyboard-operable native controls, visible focus, labels, loading/empty/error states, and phone-width layouts are pinned by the component tests. **Browser/mobile runtime verification and screenshots have not been performed** (plan gate: screenshots only after that pass).

### 7. JWT bearer token + role model

- **Enforcement:** `SecurityConfig` — public only `/api/auth/**` and `/actuator/health`; explicit family rules; method-level write rules (patient create/update and appointment create are `ADMIN`/`RECEPTIONIST`-only; staff directory read adds `RECEPTIONIST`); ADMIN-only catch-all for unmatched `/api/**`. Frontend mirrors are hints only (`docs/architecture/patient-journey.md`).
- **Tests:** `SecurityAuthorizationTest`; frontend `authorization.test.js` (full role × action matrix, deny-by-default).
- **Runtime evidence:** `401`/`403` handling is pinned end-to-end (e.g. `PatientsPage.test.jsx` "handles 401 by invoking the session-expiry callback…", "shows a visible permission denial on 403"); smoke script fails on any non-2xx contract deviation.

### 8. Application-level audit for mutations

- **Behavior:** successful patient create/update and appointment create/delete write `AuditEvent`s atomically with the mutation (`PatientService`, `AppointmentService` via `AuditService`); failed operations create none. `GET /api/audit` is ADMIN-only; the `AuditPage` renders only evidence columns and never `details`, metadata, or tokens. The care-operations slice extends exactly-once audit to every new mutation (see below).
- **Tests:** `adminObservesCreateAuditEventTiedToCreatedPatient`, `failedAppointmentValidationCreatesNeitherAppointmentNorAudit` (backend); `AuditPage` component tests + shell integration test (frontend).

### 9. Docker intentionally postponed

- **Evidence of absence:** no Dockerfile, no compose files, no Docker work anywhere in either plan. Scope gate held.

### 10. Educational, non-certified boundary

- **Evidence:** this document, the prominent boundary statements in `README.md`, `docs/patient-journey.md`, `docs/care-operations.md`, `docs/architecture/patient-journey.md`, `docs/architecture/care-operations.md`, `docs/runbook.md`, and the "explicitly not done" list in `docs/implementation-status.md`. No production-readiness claim exists in tracked docs.

### 11. Regulatory review, threat modelling, migrations, backups, observability, integrations, deeper testing — **Pilot/Productized horizons, not done**

- **Status:** none of these have been started; they are owner-decision gates (`docs/plan1.md` §9, `docs/plan2.md` §8) and separately approved work. The milestone stops at the Training/Portfolio acceptance review stop gate.

## Plan 2 traceability — care operations (docs/plan2.md §4)

Evidence kinds: **[tests]** automated backend/frontend suites; **[smoke]** the live sequential journey run by `scripts/smoke-patient-journey.sh` against a disposable local backend (supervisor-recorded baseline in `docs/performance.md`); **[docs]** documentation verified line-by-line against source (this task).

### §4.1 — Admission for an existing patient, server-stamped discharge, verified and audited

- **Implementation:** `AdmissionController` / `AdmissionService` / `AdmissionDtos` (backend `admission/`), `frontend/src/features/admissions/admissionApi.js`, `AdmissionsPage.jsx`, `PatientDetailPage.jsx` ("Register admission" entry).
- **[tests]** `CareOperationsApiTest`: `admissionCreatePinsNormalizedDtoContractWithVerifiedPatientReference`, `admissionCreateRejectsUnknownPatientAndMalformedBodiesWithoutPersisting`, `admissionDischargePinsServerStampedTransitionConflictAndAudit` (server-stamped `dischargedAt`, repeat discharge `409`, exactly one UPDATE event), `admissionDeleteRemainsServiceOwnedSafeAndAudited`; frontend `AdmissionsPage.test.jsx` (14 tests).
- **[smoke]** steps `admission_create` (server returns `status=ADMITTED`) and `admissions_DISCHARGED` (server-applied transition with nonblank `dischargedAt` required); timed rows in the `docs/performance.md` Task 9 baseline.
- **[docs]** `docs/care-operations.md` step 2; `docs/api.md` admissions section; `docs/architecture/care-operations.md` §3–§5.

### §4.2 — Emergency visit with `1–5` demo label and legal transitions

- **Implementation:** `EmergencyVisitController` / `EmergencyVisitService` / `EmergencyVisitDtos` (backend `emergency/`), `frontend/src/features/emergency/emergencyApi.js`, `EmergencyVisitsPage.jsx`.
- **[tests]** `CareOperationsApiTest`: `emergencyVisitCreatePinsNormalizedDtoContractWithVerifiedPatientReference`, `emergencyVisitCreateRejectsUnknownPatientAndInvalidTriageWithoutPersisting` (non-`1–5` → `400`), `emergencyVisitTransitionsPinLifecycleConflictsAndAudit` (`WAITING → IN_TREATMENT → CLOSED`; `CLOSED` terminal → `409`), `emergencyVisitDeleteRemainsServiceOwnedSafeAndAudited`; frontend `EmergencyVisitsPage.test.jsx` (14 tests).
- **[smoke]** steps `emergency_create` (`WAITING`), `emergency-visits_IN_TREATMENT`, `emergency-visits_CLOSED` — the only legal path asserted; timed rows in the Task 9 baseline.
- **[docs]** `docs/care-operations.md` step 3; `docs/api.md` emergency-visits section (neutral demo label stated verbatim).

### §4.3 — Invoice lifecycle, unique number, ADMIN/BILLING, no payment integration

- **Implementation:** `InvoiceController` / `InvoiceService` / `InvoiceDtos` / `Invoice` (DB unique constraint `uk_invoices_invoice_number`), `frontend/src/features/billing/invoiceApi.js`, `InvoicesPage.jsx` (on-page simulation statement).
- **[tests]** `CareOperationsApiTest`: `invoiceCreatePinsNormalizedDtoContractWithVerifiedPatientReference` (canonical `toPlainString()` storage — `1E+3` echoed as `1000`), `invoiceCreateRejectsInvalidReferencesAmountsAndDuplicatesWithoutPersisting` (negative/over-precision amounts `400`, duplicate → `409` without overwrite, boundary values pinned), `invoiceTransitionsPinLifecycleConflictsAndAudit` (`DRAFT → ISSUED → PAID`; terminal `PAID`/`VOID` → `409`), `invoiceDeleteRemainsServiceOwnedSafeAndAudited`; `SecurityAuthorizationTest.invoiceWritesAdmitAdminAndBillingWhileClinicalRolesPersistNothing` (RECEPTIONIST/DOCTOR/NURSE `403`); frontend `InvoicesPage.test.jsx` (11 tests).
- **[smoke]** steps `invoice_create` (unique `SMOKE-INV-…` number, `DRAFT`), `invoices_ISSUED`, `invoices_PAID`; timed rows in the Task 9 baseline.
- **[docs]** `docs/care-operations.md` step 4; `docs/api.md` invoices section; financial-simulation boundary stated in all touched docs.

### §4.4 — Status-aware dashboard with labels and smoke-asserted contract

- **Implementation:** `DashboardService` / `DashboardController` (backend `reporting/`), `countByStatus` derived queries on `AdmissionRepository`, `EmergencyVisitRepository`, `InvoiceRepository`; `frontend/src/DashboardPage.jsx` (Current activity / Totals / Invoices by status groups, generic fallback).
- **[tests]** `DashboardApiTest`: `dashboardExposesExactContractKeysWithAllZeroStatusCountsOnFreshData`, `dashboardAggregatesExactStatusBucketsSideEffectFree`; `DemoDataInitializerTest.dashboardAggregatesReflectExactFixtureComposition`; frontend `DashboardPage.test.jsx` (5 tests).
- **[smoke]** `dashboard` step requires all eleven numeric keys and totals consistent with the journey just executed; timed row in the Task 9 baseline.
- **[docs]** `docs/care-operations.md` step 5; `docs/api.md` dashboard section.

### §4.5 — Role-aware navigation and enforced RBAC agreement (incl. BILLING isolation)

- **Implementation:** `frontend/src/navigation.js`, `authorization.js`, `AppShell.jsx`; server authority unchanged in `SecurityConfig`.
- **[tests]** `SecurityAuthorizationTest`: `anonymousIsUnauthorizedOnEveryPlan2NamedSurface` (`401`), `careOperationReadsAdmitExactlyTheDocumentedRoleFamilies`, `admissionWritesAdmitTheFourRolesWhileBillingPersistsNothing`, `emergencyWritesAdmitTheFourRolesWhileBillingPersistsNothing`, `invoiceWritesAdmitAdminAndBillingWhileClinicalRolesPersistNothing` (every denied role `403`, refused writes persist nothing); frontend `navigation.test.js` (11), `authorization.test.js` (11), `AppShell.test.jsx` (10).
- **[smoke]** single-ADMIN journey; RBAC evidence remains the test suites' job by design (Task 9 contract).
- **[docs]** `docs/care-operations.md` role-boundary table; `docs/api.md` enforced role matrix.

### §4.6 — Exactly-one audit per successful mutation, none on failure, ADMIN screen

- **Implementation:** `AuditService` (unchanged) called by the three care-operations services inside their transactions; `frontend/src/features/audit/AuditPage.jsx`.
- **[tests]** `CareOperationsApiTest`: `careOperationsAuditSweepPinsExactlyOneEventCanonicalDetailsAndNoSensitiveLeakage` (exactly one event per mutation, `details` names the transition, event shape unchanged, no sensitive leakage) and `createAndDeleteCurrentlyProduceOneAuditEventEachWithSessionActor`; per-family failure pins prove `400`/`404`/`409` persist neither records nor events; frontend `AuditPage.test.jsx` (9 tests, renders an admission/invoice row with only the five evidence fields).
- **[docs]** `docs/care-operations.md` step 6; `docs/architecture/care-operations.md` §8.

### §4.7 — Coherent opt-in fixtures exercising the new flows

- **Implementation:** `DemoDataInitializer` (composite lookup-before-create keys; care-operations fixtures referencing seeded patients only).
- **[tests]** `DemoDataInitializerTest` (8 tests): `careOperationFixturesFollowTheApprovedComposition`, `careOperationReferencesResolveToExistingDemoPatients`, `seededCreatesProduceSystemActorAuditEvents` (sixteen `system` events on first seed, none on rerun), `repeatedInitializationIsIdempotentWithoutDuplicateInflation`, `dashboardAggregatesReflectExactFixtureComposition`.
- **[docs]** `docs/runbook.md` demo-seed section (composition and expected dashboard evidence).

### §4.8 — Suites, build, and smoke pass; dated baseline without capacity claim

- **[tests]** fresh worker run at the documentation revision: backend `mvn test` — **70 tests, 0 failures** (`CareOperationsApiTest` 20, `PatientJourneyApiTest` 19, `SecurityAuthorizationTest` 14, `DemoDataInitializerTest` 8, `DevAdminInitializerTest` 6, `DashboardApiTest` 2, `ArchitectureSmokeTest` 1); frontend `npm test` — **125 tests across 12 files, 0 failures**; `npm run build` — exit 0.
- **[smoke]** supervisor-recorded isolated live run: `RUNS=5`, exit 0, `SMOKE RESULT: PASS` — per-step table and environment assumptions in `docs/performance.md` (single-workstation regression tripwire; explicitly no capacity claim).

### §4.9 — Documentation describes only verified behavior; `git diff --check` clean

- **Implementation:** this task — `docs/care-operations.md`, `docs/architecture/care-operations.md`, `docs/api.md`, `docs/traceability.md`, `docs/implementation-status.md`, `docs/patient-journey.md`, `README.md`, accuracy pass over `docs/runbook.md`.
- **[docs]** every endpoint, DTO field, status value, role, dashboard key, command, and tracked path in these files was checked against source during the task; `git diff --check` runs as a gate (see verification commands).

### §4.10 — Non-clinical boundary statement unchanged everywhere

- **[docs]** the Training/Portfolio, synthetic-data, non-clinical, financial-simulation, and no-capacity boundaries appear prominently in `README.md`, `docs/care-operations.md`, both architecture documents, `docs/api.md`, `docs/runbook.md`, `docs/performance.md`, and `docs/implementation-status.md`. No tracked doc claims clinical, payment, Pilot, or production capability.

## Verification commands

```bash
cd backend && mvn test                        # 70 tests, exit 0
cd ../frontend && npm test && npm run build   # 125 tests + build, exit 0
cd .. && git diff --check                     # whitespace/conflict-marker gate
# care-operations journey smoke (backend running; see docs/runbook.md for the
# exact boot command and required environment variable names)
BASE_URL=http://127.0.0.1:5501 RUNS=3 \
HOSPITAL_SMOKE_PASSWORD="<disposable local value>" \
./scripts/smoke-patient-journey.sh            # SMOKE RESULT: PASS, exit 0
```

Performance baselines for the same script are recorded in `docs/performance.md` (single-workstation, single-digit-row dataset; explicitly no production-capacity claim).

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
| 10 | Documentation, portfolio evidence, and stop gate | this docs change | pending review |

Publication state: the Plan 2 PRs (#15–#23) are merged into `main` as of 2026-09-10. Task 10's documentation change is not yet merged or accepted; it remains pending the stop-gate acceptance review, which runs from a clean checkout.
