# MediCore — Next Development Plan

> **Status:** Approved planning target for the next Training/Portfolio milestone. This document plans implementation; it does not claim that the listed work is already complete.

**Goal:** Turn MediCore from a protected dashboard backed by broad CRUD APIs into one coherent, demonstrable patient journey: register and find a patient, review and update their record, schedule an appointment with an existing professional, and verify authorization and audit evidence.

**Architecture:** Preserve the Java 21/Spring Boot 3 modular monolith and React/Vite client defined by the PDR. Develop one vertical workflow across UI, API, application logic, persistence, authorization, and audit before adding more module screens. The server remains authoritative for validation and authorization; the browser accesses domain behavior only through REST/JSON APIs.

**Tech stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security, JWT bearer authentication, H2 for local development/tests, PostgreSQL profile for later production-like validation, React 19, and Vite 7.

---

## 1. PDR-derived direction

`docs/pdr.md` defines MediCore as a **modular hospital-management training system** that demonstrates realistic workflows without presenting itself as production-certified clinical software. It also establishes:

- a Spring Boot modular monolith;
- REST/JSON contracts;
- H2 for lightweight local work and a PostgreSQL profile for later production-like use;
- a React/Vite frontend;
- JWT bearer authentication with roles;
- application-level audit events for mutating operations;
- Docker as intentionally postponed.

The next milestone must therefore improve **workflow depth**, not API breadth. The repository already contains many domain controllers, while the current authenticated frontend still presents most modules as non-clickable “API ready” tiles. The highest-value next step is a complete Patient Journey vertical slice that a reviewer can use and understand.

This plan preserves `docs/pdr.md` unchanged as the canonical product boundary.

## 2. Current-state gap analysis

### Available foundation

- Dedicated login page and session-based frontend authentication.
- Backend-enforced JWT authentication and endpoint-family role rules.
- Patient create/read/list/update API.
- Staff and appointment API surfaces.
- Application audit store and ADMIN audit endpoint.
- Dashboard summary endpoint.
- Backend authorization integration-test foundation.

### Gaps blocking a real workflow

1. Module tiles do not open functional pages.
2. Patient and appointment operations are not exposed as usable frontend workflows.
3. Appointment references are accepted as raw strings rather than verified domain relationships.
4. Several controllers return persistence entities directly instead of explicit response DTOs.
5. Frontend automated test tooling and tests are absent.
6. Loading, empty, validation, conflict, authorization, and retry states are not consistently specified.
7. Existing API coverage does not prove one complete cross-module business journey.
8. Performance has been sampled operationally, but the repository has no repeatable workflow budget or measurement procedure.

## 3. Scope and non-goals

### In scope for the next Training/Portfolio milestone

- Patient list, search, registration, detail, and edit screens.
- Staff/professional lookup required for appointment scheduling.
- Appointment list and create workflow linked to existing records.
- Backend DTO and relationship strengthening required by that workflow.
- Server-side authorization and audit evidence for each mutation.
- Accessible, mobile-responsive loading, empty, validation, `401`, and `403` states.
- Coherent synthetic demo data and Portfolio evidence.
- Repeatable backend, frontend, authorization, runtime, and performance checks.

### Explicit non-goals

- Production clinical certification or a claim of regulatory readiness.
- Real patient or hospital data.
- Docker/containerization in this milestone.
- HL7/FHIR, PACS/Orthanc, payment-gateway, or insurer-network integrations.
- Microservices, Kubernetes, multi-tenancy, or distributed infrastructure.
- Invented LGPD retention periods, legal interpretations, approval thresholds, or hospital policy.
- Implementing every backend module as a frontend page before the Patient Journey is proven.

## 4. Release horizons

### Horizon A — Training/Portfolio Demo (current target)

Deliver one polished synthetic-data workflow with clear architecture, role enforcement, auditability, automated tests, runtime proof, and Portfolio documentation.

### Horizon B — Real-user Pilot (future decision gate)

Use limited authorized data only after workflow validation, privacy/security review, backup and restore proof, monitoring, support ownership, and measurable acceptance criteria are approved. This horizon is not authorized by this plan.

### Horizon C — Productized release (future gate after Pilot evidence)

Consider repeatable provisioning, stronger identity, configurable policy, lifecycle operations, external integrations, commercial/legal readiness, and evidence-based capacity targets only after a Pilot proves the actual boundary.

---

## 5. Sequential implementation roadmap

### Task 1: Characterize the current Patient Journey contracts

**Objective:** Freeze the current patient, staff, appointment, authorization, and audit behavior in integration tests before refactoring.

**Files:**

- Create: `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java`
- Modify only if a reusable test helper is justified: `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java`
- Read/verify: `backend/src/main/java/com/mamtrex/hospital/patient/PatientController.java`
- Read/verify: `backend/src/main/java/com/mamtrex/hospital/staff/StaffMemberController.java`
- Read/verify: `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentController.java`
- Read/verify: `backend/src/main/java/com/mamtrex/hospital/audit/AuditController.java`

**Test-first steps:**

1. Add an isolated in-memory H2 integration test with disposable users and synthetic records.
2. Assert anonymous requests are `401`.
3. Assert an allowed receptionist/admin can create and search a patient.
4. Assert a denied role receives `403` for restricted actions.
5. Characterize current appointment creation, including its acceptance of string identifiers.
6. Assert successful mutations produce an audit event visible to ADMIN.

**Run:**

```bash
cd backend
mvn -Dtest=PatientJourneyApiTest test
```

**Expected evidence:** The new characterization test passes against current behavior and clearly marks weak string-reference behavior that later tasks intentionally change.

**Completion gate:** No production behavior is changed; tests are isolated from `backend/data/` and contain no real personal data.

### Task 2: Add frontend test tooling before feature code

**Objective:** Establish a real frontend test command before later tasks depend on frontend tests.

**Files:**

- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/vitest.config.js`
- Create: `frontend/src/test/setup.js`
- Create: `frontend/src/LoginPage.test.jsx`

**Test-first steps:**

1. Add pinned development dependencies for Vitest, jsdom, and React Testing Library.
2. Add a `test` script that runs once and exits non-zero on failure.
3. Write a login-page behavior test for labels, keyboard submission, loading disablement, and visible invalid-login feedback.
4. Run the test and resolve configuration only; do not refactor unrelated UI.

**Run:**

```bash
cd frontend
npm test
npm run build
```

**Expected evidence:** The test runner executes real tests, the login test passes, and the production build still succeeds.

**Completion gate:** `npm test` is a real quality gate, not a wrapper that always exits successfully.

### Task 3: Normalize Patient Journey API contracts

**Objective:** Stop exposing persistence entities directly and define stable request/response DTOs for patients, professionals, and appointments.

**Files:**

- Create: `backend/src/main/java/com/mamtrex/hospital/patient/PatientDtos.java`
- Modify: `backend/src/main/java/com/mamtrex/hospital/patient/PatientController.java`
- Modify: `backend/src/main/java/com/mamtrex/hospital/patient/PatientService.java`
- Create: `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentDtos.java`
- Modify: `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentController.java`
- Test: `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java`

**Test-first steps:**

1. Change integration-test expectations to explicit DTO fields and stable JSON shapes.
2. Verify sensitive/internal persistence fields are absent from responses.
3. Add validation cases for malformed UUIDs, blank required values, and invalid dates/status values.
4. Implement the smallest DTO mapping that satisfies the contract.
5. Keep HTTP concerns in controllers and workflow rules in services.

**Run:**

```bash
cd backend
mvn -Dtest=PatientJourneyApiTest test
mvn test
```

**Expected evidence:** DTO contract tests pass and the full backend suite has zero failures.

**Completion gate:** No controller in the Patient Journey returns a mutable JPA entity as its public response contract.

### Task 4: Replace raw appointment references with verified relationships

**Objective:** Ensure an appointment cannot reference a nonexistent patient or professional.

**Files:**

- Modify: `backend/src/main/java/com/mamtrex/hospital/appointment/Appointment.java`
- Modify: `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentRepository.java`
- Create: `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentService.java`
- Modify: `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentController.java`
- Read/modify as required: `backend/src/main/java/com/mamtrex/hospital/patient/PatientRepository.java`
- Read/modify as required: `backend/src/main/java/com/mamtrex/hospital/staff/StaffMemberRepository.java`
- Test: `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java`

**Test-first steps:**

1. Add failing tests for missing patient, missing professional, disabled/ineligible professional if represented by current data, and valid creation.
2. Define UUID-based request fields and repository validation.
3. Introduce an appointment service that resolves references and owns creation/deletion rules.
4. Preserve audit creation only after a successful persisted mutation.
5. Determine a safe H2/PostgreSQL migration strategy before changing persisted columns; do not silently destroy existing local data.

**Run:**

```bash
cd backend
mvn -Dtest=PatientJourneyApiTest test
mvn test
```

**Expected evidence:** Invalid references return a documented client error; valid records are linked and auditable.

**Completion gate:** No new appointment can contain an arbitrary unresolved patient or professional identifier.

### Task 5: Create a lightweight authenticated navigation boundary

**Objective:** Make permitted modules navigable without introducing a heavy routing dependency unless the existing UI proves it necessary.

**Files:**

- Create: `frontend/src/navigation.js`
- Create: `frontend/src/AppShell.jsx`
- Modify: `frontend/src/DashboardPage.jsx`
- Modify: `frontend/src/main.jsx`
- Create: `frontend/src/AppShell.test.jsx`
- Modify: `frontend/src/style.css`

**Test-first steps:**

1. Test that only role-permitted destinations appear.
2. Test dashboard, patients, and appointments screen selection.
3. Test browser refresh fallback to the dashboard if URL persistence is not implemented.
4. Extract authenticated layout from the dashboard without changing login/session behavior.
5. Use semantic buttons/links with focus states; keep mobile navigation on one usable line or an accessible compact menu.

**Run:**

```bash
cd frontend
npm test
npm run build
```

**Expected evidence:** A permitted user can navigate among implemented screens; unauthorized modules are absent and direct API calls remain server-protected.

**Completion gate:** Navigation no longer presents unimplemented tiles as usable pages, and UI filtering is not treated as authorization.

### Task 6: Build patient list and search

**Objective:** Let authorized users find and open synthetic patient records.

**Files:**

- Create: `frontend/src/features/patients/patientApi.js`
- Create: `frontend/src/features/patients/PatientsPage.jsx`
- Create: `frontend/src/features/patients/PatientsPage.test.jsx`
- Modify: `frontend/src/navigation.js`
- Modify: `frontend/src/style.css`

**Test-first steps:**

1. Test initial loading, populated result, empty result, API failure, `401`, and `403` states.
2. Test search submission and safe rendering of returned values.
3. Implement patient transport through `frontend/src/api.js`; components must not call `fetch` directly.
4. Add responsive list/card presentation suitable for a phone.
5. Add keyboard-accessible patient selection.

**Run:**

```bash
cd frontend
npm test
npm run build
```

**Expected evidence:** Search calls `GET /api/patients?q=...`, states are visible and accessible, and an expired session returns to Login.

**Completion gate:** A reviewer can find and select a synthetic patient without using API tools.

### Task 7: Build patient registration, detail, and edit

**Objective:** Complete the patient-record portion of the workflow with validation and auditable mutations.

**Files:**

- Create: `frontend/src/features/patients/PatientForm.jsx`
- Create: `frontend/src/features/patients/PatientDetailPage.jsx`
- Create: `frontend/src/features/patients/PatientForm.test.jsx`
- Modify: `frontend/src/features/patients/patientApi.js`
- Modify: `frontend/src/features/patients/PatientsPage.jsx`
- Modify: `frontend/src/style.css`
- Test: `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java`

**Test-first steps:**

1. Test required fields, email validation, duplicate medical-record handling, submit disablement, cancellation, success, and server validation errors.
2. Implement create using `POST /api/patients` and edit using `PUT /api/patients/{id}`.
3. Keep identifiers read-only after creation unless the backend contract explicitly supports a safe change.
4. Return the user to the detail view with visible success feedback.
5. Verify corresponding audit evidence as ADMIN.

**Run:**

```bash
cd frontend
npm test
npm run build
cd ../backend
mvn -Dtest=PatientJourneyApiTest test
```

**Expected evidence:** A patient can be registered, reopened, and edited; invalid or unauthorized mutations fail visibly without losing form data.

**Completion gate:** The patient flow is usable on desktop and mobile and all writes are server-validated and audited.

### Task 8: Build professional selection and appointment scheduling

**Objective:** Schedule an appointment for an existing patient with an existing professional.

**Files:**

- Create: `frontend/src/features/appointments/appointmentApi.js`
- Create: `frontend/src/features/appointments/AppointmentsPage.jsx`
- Create: `frontend/src/features/appointments/AppointmentForm.jsx`
- Create: `frontend/src/features/appointments/AppointmentsPage.test.jsx`
- Create: `frontend/src/features/staff/staffApi.js`
- Modify: `frontend/src/navigation.js`
- Modify: `frontend/src/features/patients/PatientDetailPage.jsx`
- Modify: `frontend/src/style.css`
- Test: `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java`

**Test-first steps:**

1. Test loading existing patients and professionals into labeled selections.
2. Test valid date/time, type, and status input plus unavailable API/error states.
3. Test that IDs submitted are selected domain records, not manually typed opaque strings.
4. Implement appointment creation and list refresh.
5. Add “Schedule appointment” from patient detail with the patient preselected.
6. Verify denied roles cannot perform the action and receive a clear `403` state if the server refuses it.

**Run:**

```bash
cd frontend
npm test
npm run build
cd ../backend
mvn -Dtest=PatientJourneyApiTest test
mvn test
```

**Expected evidence:** The appointment is linked to real synthetic records, appears in the list, and creates audit evidence.

**Completion gate:** A reviewer completes registration → search/detail → appointment without entering raw IDs or leaving the browser.

### Task 9: Align role-aware actions and security regression coverage

**Objective:** Make UI capabilities match backend policy while preserving deny-by-default server enforcement.

**Files:**

- Modify: `frontend/src/navigation.js`
- Modify: `frontend/src/features/patients/PatientsPage.jsx`
- Modify: `frontend/src/features/patients/PatientDetailPage.jsx`
- Modify: `frontend/src/features/appointments/AppointmentsPage.jsx`
- Create: `frontend/src/authorization.js`
- Create: `frontend/src/authorization.test.js`
- Modify: `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java`

**Test-first steps:**

1. Define a resource/action permission map for the implemented UI actions only.
2. Test ADMIN, DOCTOR, NURSE, RECEPTIONIST, and a denied role against read/create/update actions.
3. Add backend tests for the same endpoint/action matrix.
4. Hide or disable disallowed actions for usability, but assert direct calls still return `403`.
5. Preserve session clearing on `401`; retain the session and show denial on `403`.

**Run:**

```bash
cd frontend
npm test
npm run build
cd ../backend
mvn -Dtest=SecurityAuthorizationTest,PatientJourneyApiTest test
```

**Expected evidence:** A documented test matrix shows frontend capability hints and backend enforcement agree.

**Completion gate:** No implemented write action depends solely on frontend hiding.

### Task 10: Add an ADMIN audit evidence screen

**Objective:** Make mutation evidence visible during the demo without exposing audit data to non-admin roles.

**Files:**

- Create: `frontend/src/features/audit/auditApi.js`
- Create: `frontend/src/features/audit/AuditPage.jsx`
- Create: `frontend/src/features/audit/AuditPage.test.jsx`
- Modify: `frontend/src/navigation.js`
- Modify: `frontend/src/style.css`
- Test: `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java`

**Test-first steps:**

1. Test ADMIN visibility and non-admin absence.
2. Test loading, empty, error, and safe event rendering.
3. Implement read-only `GET /api/audit` access through the API adapter.
4. Show patient and appointment mutations with timestamp, action, entity type, and identifier fields already provided by the API.
5. Do not display tokens, credentials, or sensitive request bodies.

**Run:**

```bash
cd frontend
npm test
npm run build
cd ../backend
mvn -Dtest=SecurityAuthorizationTest,PatientJourneyApiTest test
```

**Expected evidence:** ADMIN can trace the demonstrated writes; non-admin API access remains `403`.

**Completion gate:** Audit evidence supports the Portfolio demo without claiming regulatory-grade audit compliance.

### Task 11: Add coherent idempotent synthetic demo data

**Objective:** Provide a repeatable demo journey without real personal data or duplicate inflation.

**Files:**

- Create: `backend/src/main/java/com/mamtrex/hospital/bootstrap/DemoDataInitializer.java`
- Create: `backend/src/test/java/com/mamtrex/hospital/bootstrap/DemoDataInitializerTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `docs/runbook.md`

**Test-first steps:**

1. Test that the initializer is disabled by default.
2. Test a dedicated opt-in demo profile using obviously synthetic names and identifiers.
3. Test referential integrity among patient, professional, and appointment records.
4. Run initialization twice and assert record counts do not increase on the second run.
5. Keep login secrets outside source and do not create public default passwords.

**Run:**

```bash
cd backend
mvn -Dtest=DemoDataInitializerTest test
mvn test
```

**Expected evidence:** An opt-in run produces one coherent synthetic journey; a second run is idempotent.

**Completion gate:** Demo data is synthetic, deterministic, referentially valid, opt-in, and secret-free.

### Task 12: Establish repeatable performance evidence

**Objective:** Detect regressions in the demonstrated workflow without inventing production capacity claims.

**Files:**

- Create: `scripts/smoke-patient-journey.sh`
- Create: `docs/performance.md`
- Modify if justified: `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardController.java`
- Test if dashboard implementation changes: `backend/src/test/java/com/mamtrex/hospital/reporting/DashboardControllerTest.java`

**Test-first steps:**

1. Add a smoke script that fails on non-2xx responses, missing response fields, or broken workflow steps.
2. Record local environment assumptions separately from results.
3. Capture repeated login, patient search, patient detail, appointment creation, appointment list, and dashboard timings.
4. Define a Training/Portfolio budget from a reviewed baseline before enforcing numeric thresholds.
5. Profile dashboard count queries; optimize only if evidence identifies them as the bottleneck.
6. Re-run backend and frontend suites after any optimization.

**Run:**

```bash
./scripts/smoke-patient-journey.sh
cd backend && mvn test
cd ../frontend && npm test && npm run build
```

**Expected evidence:** A dated, reproducible local baseline and pass/fail smoke result; no unsupported production-scale claim.

**Completion gate:** Performance claims in documentation point to reproducible commands and recorded environment context.

### Task 13: Produce Portfolio and operational evidence

**Objective:** Document what the Training/Portfolio milestone truly demonstrates and how to verify it.

**Files:**

- Modify: `README.md`
- Modify: `docs/api.md`
- Modify: `docs/runbook.md`
- Modify: `docs/implementation-status.md`
- Create: `docs/patient-journey.md`
- Create: `docs/architecture/patient-journey.md`
- Create: `docs/traceability.md`

**Steps:**

1. Document the workflow, roles, API contracts, failure states, and audit evidence from implemented behavior.
2. Add an architecture diagram showing UI → API adapter → controller/application service → repository/audit boundaries.
3. Include synthetic screenshots only after browser/mobile verification.
4. Preserve the educational/non-clinical boundary prominently.
5. Link each accepted requirement to tests and runtime evidence.
6. Run a documentation accuracy pass against source and commands.

**Run:**

```bash
cd backend && mvn test
cd ../frontend && npm test && npm run build
cd .. && git diff --check
```

**Expected evidence:** Documentation references only existing endpoints, files, commands, and observed behavior.

**Completion gate:** A reviewer can understand, run, and verify the Patient Journey without private credentials or internal environment details.

---

## 6. PDR traceability matrix

| PDR requirement | Planned tasks | Required evidence | Horizon |
|---|---|---|---|
| Realistic modular hospital workflow | 1, 3–10 | Completed registration → search/detail → appointment → audit journey | Training/Portfolio |
| Java 21 + Spring Boot 3 modular monolith | 1, 3, 4, 9 | Full Maven suite and architecture-boundary documentation | Training/Portfolio |
| REST/JSON API | 1, 3, 4, 6–10 | DTO contract tests and updated `docs/api.md` | Training/Portfolio |
| H2 local development | 1, 11 | Isolated in-memory tests and opt-in idempotent synthetic demo data | Training/Portfolio |
| PostgreSQL production-like profile | Pilot preparation | Migration rehearsal and restore evidence after owner approval | Pilot |
| React + Vite frontend | 2, 5–10 | Automated frontend tests and production build | Training/Portfolio |
| JWT bearer token + role model | 1, 5, 9, 10 | `401`/`403` and role/action matrix tests | Training/Portfolio |
| Application-level audit for mutations | 1, 7, 8, 10 | ADMIN-visible patient and appointment audit events | Training/Portfolio |
| Docker intentionally postponed | Scope gate | No Docker work in the current milestone | Training/Portfolio |
| Educational, non-certified boundary | 13 and every release gate | README/status wording and no production-readiness claim | All horizons |
| Regulatory review, threat modelling, migrations, backups, observability, integrations, deeper testing before clinical use | Pilot/Productized gates | Owner/specialist decisions and separately approved evidence | Pilot/Productized |

## 7. Quality gates

Every implementation task must preserve these gates:

1. **Backend:** `cd backend && mvn test` passes with zero failures.
2. **Frontend:** after Task 2, `cd frontend && npm test && npm run build` passes.
3. **Authorization:** anonymous access returns `401`; authenticated denied access returns `403`; permitted actions succeed.
4. **Data integrity:** appointments cannot reference missing patients or professionals.
5. **Audit:** successful patient and appointment mutations create traceable events; failed operations do not create success events.
6. **Runtime smoke:** the complete synthetic Patient Journey succeeds through the user-facing frontend/API boundary.
7. **UX:** keyboard operation, visible focus, labels, errors, loading, empty states, and phone-width layout are reviewed.
8. **Security:** no credentials, tokens, real personal data, private addresses, or machine-local paths enter tracked files.
9. **Documentation:** every endpoint, command, path, and behavior claim is checked against source.
10. **Scope:** `git diff --check` passes and unrelated modules are unchanged.

## 8. Definition of Done — next Training/Portfolio milestone

The milestone is complete only when all of the following are proven:

- A logged-in authorized user can register a synthetic patient.
- The user can search for that patient, open details, and edit allowed fields.
- The user can schedule an appointment by selecting existing patient and professional records.
- Invalid references, validation errors, `401`, and `403` states are clear and tested.
- Backend authorization is enforced independently of frontend visibility.
- Patient and appointment mutations create ADMIN-visible audit evidence.
- The workflow is usable at desktop and phone widths with keyboard-accessible controls.
- Backend tests, frontend tests, frontend build, and workflow smoke script all pass.
- Demo data is opt-in, synthetic, coherent, idempotent, and contains no public password.
- Documentation and Portfolio evidence describe only verified functionality.
- The project still states clearly that it is educational and not certified for clinical deployment.

## 9. Owner decisions required before a Pilot

The following are explicit decision gates, not assumptions for developers:

- Which real hospital role and workflow will validate the Pilot?
- Which jurisdiction and privacy/LGPD specialist will review personal-data handling?
- What retention, correction, deletion, and access-request policy applies?
- Which hosting boundary and operator are approved?
- What backup RPO/RTO and restore-test frequency are required?
- Is MFA or SSO required, and which identity source is authoritative?
- Which integration standards and external systems are genuinely needed?
- Who owns incident response, support hours, monitoring, and user onboarding?
- What Pilot dataset is permitted, and how will consent/authority be demonstrated?

No Pilot may begin until these decisions are recorded and the required security, privacy, migration, backup/restore, and observability work is separately planned.

## 10. Recommended execution order and stop gate

Execute Tasks 1–13 sequentially. Tasks 1–4 establish trustworthy contracts and relationships; Tasks 5–10 deliver the visible workflow; Tasks 11–13 make the demo repeatable, measurable, and presentable.

**STOP GATE:** After Task 13, conduct a formal Training/Portfolio acceptance review. Do not start Pilot work, introduce real hospital data, add external clinical integrations, or claim production readiness without explicit owner authorization and the decisions in Section 9.
