# Requirements Traceability — Training/Portfolio Milestone

Expands the PDR traceability matrix (`docs/plan1.md` §6) with the actual tasks, tests, and runtime evidence behind each requirement. Scope statement: this is the **Training/Portfolio** milestone of an educational, non-clinical project — nothing here is a production-readiness or clinical-certification claim. Pilot-gated rows are explicitly marked *not done*.

## Requirement-by-requirement traceability

### 1. Realistic modular hospital workflow

- **Tasks:** 1, 3–10 — commit map: Task 1 `53d9e62`, Task 3 `d4f0516`, Task 4 `e3bbc66`, Task 5 `fbfa290`, Task 6 `15f20de` (#1), Task 7 `33f2839` (#2), Task 8 `40323b3` (#3), Task 9 `b49e549` (#4), Task 10 `968d9c9` (#5).
- **Demonstrated journey:** login → register → search/detail → edit → schedule → audit (`docs/patient-journey.md`).
- **Tests:** backend `PatientJourneyApiTest` (18 tests — e.g. `receptionistCreatesThenSearchesPatientByFullName`, `verifiedReferencesCreateAppointmentWithStableContract`); frontend `PatientsPage.test.jsx` (registration/edit/search/selection states), `AppointmentsPage.test.jsx` (scheduling flow), `AppShell.test.jsx` (navigation shell integration).
- **Runtime evidence:** `scripts/smoke-patient-journey.sh` performs the same journey over the API (login → patient create → search → detail → appointment create → list → dashboard) and fails non-zero on any contract deviation; the ADMIN Audit screen displays the mutations the journey produced.

### 2. Java 21 + Spring Boot 3 modular monolith

- **Tasks:** 1, 3, 4, 9 (commits `53d9e62`, `d4f0516`, `e3bbc66`, `b49e549`).
- **Architecture boundaries:** `docs/architecture/patient-journey.md` — controllers are narrow HTTP/DTO mappers; workflow/reference/audit rules live in services (`PatientService`, `AppointmentService`); shared error mapping lives only in `GlobalExceptionHandler`; `ArchitectureSmokeTest` guards the boundary.
- **Tests:** `mvn test` from `backend/` — 32 tests, 0 failures (`PatientJourneyApiTest` 18, `SecurityAuthorizationTest` 9, `DemoDataInitializerTest` 4, `ArchitectureSmokeTest` 1).

### 3. REST/JSON API

- **Tasks:** 1, 3, 4, 6–10.
- **Contract truth:** `docs/api.md` (endpoints, role matrix, error contract, demo-data flag).
- **Tests:** DTO field-set pins — `patientResponsesExposeStableDtoFieldsWithoutPersistenceInternals`, `appointmentDetailAndListExposeDtoContract`, `staffListExposesDtoContractForProfessionalSelection`; error contract — `blankRequiredPatientValuesReturn400`, `invalidPatientDateOfBirthReturns400`, `invalidAppointmentStatusReturns400`, `malformedAppointmentReferencesReturn400`, `malformedUuidPathReturnsClientErrorNotServerError`.
- **Runtime evidence:** the smoke script asserts exact response fields (`accessToken`/`roles`, `id` fields, arrays, dashboard count keys) on every step.

### 4. H2 local development

- **Tasks:** 1, 11 (commit `016b56a`, #6).
- **Behavior:** file-backed H2 under `backend/data/` for local review; all integration tests run against isolated in-memory H2 (`jdbc:h2:mem:…`), never the file store; the H2 console is disabled (`application.yml`: `h2.console.enabled: false`).
- **Tests:** `DemoDataInitializerTest` (4 tests: flag-on seeding, referential integrity, idempotency, default-off gate).
- **Runtime evidence:** opt-in `MEDICORE_DEMO_SEED=true` seeds a 3-patient / 2-professional / 2-appointment synthetic cohort, idempotent across restarts (verify steps in `docs/runbook.md`).

### 5. PostgreSQL production-like profile — **Pilot horizon, not done**

- **Status:** the `postgres` Spring profile exists with runtime-provided `DB_URL`/`DB_USER`/`DB_PASSWORD`, but migration rehearsal and restore evidence are Pilot-phase work requiring owner approval. No PostgreSQL run has been performed or evidenced in this milestone.

### 6. React + Vite frontend

- **Tasks:** 2, 5–10 (commits `a91fe9f`, `fbfa290`, `15f20de` #1, `33f2839` #2, `40323b3` #3, `b49e549` #4, `968d9c9` #5).
- **Tests:** `cd frontend && npm test` — 61 tests across 7 files (`LoginPage.test.jsx`, `AppShell.test.jsx`, `authorization.test.js`, `PatientsPage.test.jsx`, `PatientForm.test.jsx`, `AppointmentsPage.test.jsx`, `AuditPage.test.jsx`); `npm run build` — production bundle exit 0.
- **Runtime evidence:** dev/preview servers on port `5502` proxy `/api` to loopback `5501` (`vite.config.js`); keyboard-operable native controls, visible focus, labels, loading/empty/error states, and phone-width layouts are pinned by the component tests. **Browser/mobile runtime verification and screenshots have not been performed** (plan gate: screenshots only after that pass).

### 7. JWT bearer token + role model

- **Tasks:** 1, 5, 9, 10.
- **Enforcement:** `SecurityConfig` — public only `/api/auth/**` and `/actuator/health`; explicit family rules; method-level write rules (patient create/update and appointment create are `ADMIN`/`RECEPTIONIST`-only; staff directory read adds `RECEPTIONIST`); ADMIN-only catch-all for unmatched `/api/**`. Frontend mirrors are hints only (`docs/architecture/patient-journey.md`).
- **Tests:** `SecurityAuthorizationTest` (9 tests — `anonymousDashboardIsRejected`, `anonymousAuditIsRejected`, `healthIsPublic`, `adminCanAccessDashboardAndAudit`, `nurseCanAccessDashboardAndNursingEndpointButIsForbiddenFromAudit`, `patientCreateEnforcesAdminReceptionistOnlyWrites`, `patientUpdateEnforcesAdminReceptionistOnlyWrites`, `appointmentCreateEnforcesAdminReceptionistOnlyWrites`, `staffDirectoryAllowsReceptionistReadWhileWritesStayAdminHrOnly`); frontend `authorization.test.js` (6 tests, full role × action matrix, deny-by-default).
- **Runtime evidence:** `401`/`403` handling is pinned end-to-end (e.g. `PatientsPage.test.jsx` "handles 401 by invoking the session-expiry callback…", "shows a visible permission denial on 403"); smoke script fails on any non-2xx contract deviation.

### 8. Application-level audit for mutations

- **Tasks:** 1, 7, 8, 10 (Task 10 screen: commit `968d9c9`, #5).
- **Behavior:** successful patient create/update and appointment create/delete write `AuditEvent`s atomically with the mutation (`PatientService`, `AppointmentService` via `AuditService`); failed operations create none. `GET /api/audit` is ADMIN-only; the `AuditPage` renders only evidence columns and never `details`, metadata, or tokens.
- **Tests:** `adminObservesCreateAuditEventTiedToCreatedPatient`, `failedAppointmentValidationCreatesNeitherAppointmentNorAudit` (backend); 8 `AuditPage` component tests + shell integration test (frontend).
- **Runtime evidence:** perform the journey as ADMIN, open Audit — CREATE/UPDATE Patient and CREATE Appointment events appear newest-first. Note: rows written by the opt-in demo seeder bypass services and produce no audit events (documented in `docs/runbook.md`).

### 9. Docker intentionally postponed

- **Evidence of absence:** no Dockerfile, no compose files, no Docker work anywhere in Tasks 1–13. Scope gate held.

### 10. Educational, non-certified boundary

- **Tasks:** 13 and every release gate.
- **Evidence:** this document, the prominent boundary statements in `README.md`, `docs/patient-journey.md`, `docs/architecture/patient-journey.md`, `docs/runbook.md`, and the "explicitly not done" list in `docs/implementation-status.md`. No production-readiness claim exists in tracked docs.

### 11. Regulatory review, threat modelling, migrations, backups, observability, integrations, deeper testing — **Pilot/Productized horizons, not done**

- **Status:** none of these have been started; they are owner-decision gates (`docs/plan1.md` §9) and separately approved work. The milestone stops at the Training/Portfolio acceptance review stop gate.

## Verification commands

```bash
cd backend && mvn test                        # 32 tests, exit 0
cd ../frontend && npm test && npm run build   # 61 tests + build, exit 0
cd .. && git diff --check                     # whitespace/conflict-marker gate
# journey smoke (backend running; see docs/runbook.md for the exact boot command)
BASE_URL=http://127.0.0.1:5501 RUNS=3 \
HOSPITAL_SMOKE_PASSWORD="<disposable local value>" \
./scripts/smoke-patient-journey.sh            # SMOKE RESULT: PASS, exit 0
```

Performance baselines for the same script are recorded in `docs/performance.md` (single-workstation, single-digit-row dataset; explicitly no production-capacity claim).

## Task → commit index

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
| 13 | Produce Portfolio and operational evidence | this docs change | pending review |
