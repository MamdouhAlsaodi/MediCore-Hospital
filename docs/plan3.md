# MediCore — Phase 3 Plan: Multi-Branch Operations & Resource Coordination (Training/Portfolio)

> **Status:** Owner-approved planning target. This document defines proposed implementation work; none of the Phase 3 capabilities below are claimed as delivered until their acceptance evidence exists.
>
> **Boundary:** MediCore remains an educational, non-clinical Training/Portfolio system using synthetic data only. This phase demonstrates the architecture and workflows of one hospital organization with several branches; it is not SaaS multi-tenancy, certified medical software, a Pilot, or a production hospital deployment.

---

## 1. Phase mission

Phase 1 proved a synthetic patient and appointment journey. Phase 2 extended it through admissions, emergency visits, simulated invoices, status-aware reporting, RBAC, audit, fixtures, smoke evidence, and truthful Portfolio documentation.

Phase 3 demonstrates a harder enterprise story: **one hospital organization coordinates several branches without mixing their operational data**. An authorized user acts through an explicit assignment, selects an allowed branch, completes a branch-scoped patient journey, assigns and transfers a bed safely, schedules staff only inside modeled availability, compares branches from an authorized command center, and reviews branch-aware audit evidence.

The phase prioritizes organizational scope and resource invariants over adding unrelated clinical modules. Laboratory, radiology, pharmacy, insurer, payment, and real clinical workflows do not enter this milestone.

### Portfolio story

A reviewer can sign in using a synthetic account and prove this sequence:

1. The session identifies the user's available acting assignments rather than treating identity and role as the same concept.
2. The user selects an assigned branch; the server issues a branch-bound acting context and rejects unassigned branches.
3. Patient, staff, appointment, admission, emergency, invoice, bed, dashboard, and audit reads delivered by this phase are scoped by the server to that context.
4. A receptionist registers a synthetic patient and books an available professional without creating an overlap.
5. An authorized operator admits the patient into an available bed, transfers the patient atomically, and discharges them; bed state follows the admission lifecycle without double assignment.
6. An organization-scoped administrator compares branches in a command center and drills into a branch-filtered view.
7. An administrator verifies who acted, under which assignment and branch, with no secrets or request bodies stored in audit details.

## 2. Truthful current baseline

The following statements describe the accepted source at the start of Phase 3, not the proposed destination.

### Delivered baseline

- Backend: Java 21, Spring Boot 3, REST/JSON, JPA, H2 local profile, PostgreSQL dependency/profile for later use, JWT bearer authentication, role-based route rules, and application audit.
- Frontend: React 19 + Vite, an in-memory `AppShell` destination model, a shared `apiFetch` adapter, session storage, role-aware navigation, Vitest, and Testing Library.
- Implemented screens: Dashboard, Patients, Appointments, Admissions, Emergency Visits, Invoices, and ADMIN Audit.
- Accepted tests/evidence at Plan 2 closure: backend 70 tests; frontend 125 tests across 12 files; production frontend build; 15-step synthetic smoke journey; dated single-workstation regression baseline.
- Admission lifecycle: `ADMITTED → DISCHARGED` with server-stamped discharge time.
- Emergency lifecycle: `WAITING → IN_TREATMENT → CLOSED`, with the `1–5` field explicitly a neutral demo label carrying no clinical meaning.
- Simulated invoice lifecycle: `DRAFT → ISSUED → PAID | VOID`, with unique invoice numbers and no payment processing.
- Audit: successful mutations emit one event; pinned `400`, `404`, and `409` failures emit none.

### Branchless and resource-management gaps

- No `Organization` or `Branch` model exists.
- `Department` is raw CRUD with `code`, `name`, `specialty`, and `location`; it has no branch ownership.
- `UserAccount` stores a set of global roles. Identity, acting role, assignment, and organizational scope are not separated.
- `JwtService` issues username and global role claims. `JwtFilter` reloads the account but creates global authorities; no assignment or branch context exists.
- `AuthController.login` returns one token, username, and role list; it cannot select or switch an acting assignment.
- Patient, staff, appointment, admission, emergency-visit, invoice, and audit records carry no branch identifier.
- `/api/beds` is raw entity CRUD. `Bed` stores free-form ward, room, bed number, occupancy status, and patient identifier strings; it has no service-owned lifecycle, verified references, uniqueness rule, DTO, transfer, or admission link.
- `StaffMember` has no branch assignment or availability model. `AppointmentService.create` verifies patient and professional IDs but accepts a time without availability or overlap checks.
- `DashboardService` performs whole-table counts and returns eleven flat keys. It has no branch filter, network view, drill-down contract, or date window.
- `AuditEvent` stores actor/action/resource/details/time only; it cannot prove the acting assignment, role, branch, department, or request correlation ID.
- The frontend stores token/username/global roles only. `apiFetch` has no acting-context contract and `AppShell` has no branch selector.
- The smoke script is a single-context journey; no automated browser/e2e runner exists in `frontend/package.json`.

## 3. Scope and boundaries

### In scope

- One organization record with multiple synthetic hospital branches.
- Branch-owned departments and explicit branch ownership for the operational records migrated in this phase.
- User assignments that separate identity from acting role and scope.
- A branch-bound authenticated context enforced by the server and mirrored by the UI.
- Cross-branch isolation for patient, staff, appointments, admissions, emergency visits, invoices, beds, dashboards, and audit reads/writes delivered by this phase.
- Normalized bed inventory plus atomic assignment, transfer, release, maintenance, and out-of-service rules.
- A minimal staff-availability model and deterministic appointment-overlap rejection.
- Organization and branch command-center views with branch-filtered drill-downs.
- Branch/assignment/correlation-aware audit events.
- An idempotent, coherent, three-branch synthetic fixture cohort.
- Responsive and accessible Portfolio UX, automated browser evidence, an extended smoke journey, a dated regression baseline, and updated architecture/API/traceability/runbook documentation.

### Explicit non-goals

- Real patient, hospital, employee, scheduling, financial, or operational data.
- Pilot operation, clinical use, certification, medical advice, clinical decision support, or a real triage protocol.
- SaaS multi-tenancy, tenant provisioning, tenant isolation claims, or cross-organization data sharing.
- Cross-hospital clinical record exchange; branches are a scope boundary inside one synthetic organization, not an interoperability network.
- HL7/FHIR, PACS, laboratory/radiology/pharmacy integrations, insurer integrations, payment gateways, money movement, tax, FX, refunds, or collections.
- Calendaring breadth, leave management, payroll, credentialing, or clinical staff-eligibility policy.
- Bed-capacity forecasting, staffing recommendations, predictive analytics, SLA/SLO claims, or production capacity claims.
- Docker, microservices, PostgreSQL migration rehearsal, backup/restore rehearsal, observability operations, incident response, MFA/SSO, or full production hardening.
- Retrofitting the repository's unrelated raw CRUD modules solely to make every table branch-aware.

### Operational support is not feature backlog

Review-account creation, temporary local credentials, local service startup, and disposable H2 runtime preparation remain runbook concerns. Product tasks may extend the opt-in synthetic seeder and account assignments needed by the demonstrated flow, but must not hardcode credentials or convert local provisioning into product behavior.

## 4. Architecture and invariants

### 4.1 Single-organization hierarchy

The hierarchy is:

```text
HospitalOrganization
└── Branch
    └── Department
```

- Phase 3 models one organization only. `organizationId` is explicit in branch/assignment contracts so the design remains truthful and unambiguous, but no tenant resolver or generic tenancy framework is introduced.
- Branch codes are unique inside the organization; department codes are unique inside a branch.
- Resources migrated by this phase carry a non-null `branchId` after the controlled synthetic-data transition.
- IDs are UUIDs. Human-readable organization, branch, department, ward, room, bed, and employee codes are lookup/display values, never authorization keys.

### 4.2 Identity, assignment, and branch context

- `UserAccount` remains identity and credential state.
- `ActingAssignment` is a separate record: user, one role, scope type (`ORGANIZATION` or `BRANCH`), optional branch, optional department, and enabled state.
- Authentication initially selects a deterministic enabled assignment for backwards-compatible login; the response also lists all enabled assignments the account may select.
- `POST /api/auth/context` accepts an assignment ID and, for organization-scoped assignments, a branch ID. The server verifies ownership, enabled state, scope, and branch membership before issuing a replacement token.
- The replacement JWT identifies the subject and selected assignment/context IDs. `JwtFilter` reloads the account, assignment, and branch on every request; authorities and acting context come from current server records, not trusted client role strings.
- Every branch-bound token is restricted to one selected branch. Switching branch or assignment issues a new token; arbitrary `branchId` query/body/header values cannot widen access.
- Organization-scoped administrators may request the network command center, but ordinary resource reads remain branch-bound unless a separately documented organization-level endpoint performs an aggregate only.
- Disabled accounts, assignments, deleted branches, assignment/subject mismatch, or malformed context fail closed (`401` for invalid authentication context; `403` for a valid identity requesting an unauthorized assignment/branch).

### 4.3 Branch ownership and cross-branch denial

- Services own authorization and branch-reference checks. UI hiding and repository filters are defense-in-depth, not the authority.
- A create request derives `branchId` from the authenticated acting context; clients do not choose ownership by posting an unchecked identifier.
- A referenced patient, staff member, department, bed, admission, or appointment must belong to the active branch. A cross-branch reference returns `404` to avoid confirming the existence of an inaccessible record; an allowed record with a forbidden action returns `403`.
- List/count queries require branch-aware repository methods. Whole-table `findAll()` and `count()` are forbidden on branch-scoped API paths after their migration task.
- Organization command-center aggregates are available only to an enabled `ADMIN` assignment with `ORGANIZATION` scope.

### 4.4 Synthetic legacy-row transition

The current repository has branchless rows and no schema-migration tool. Phase 3 therefore uses a bounded Training/Portfolio transition rather than pretending to perform a production migration:

1. Foundation tasks add nullable branch ownership fields temporarily so existing local H2 schemas can start.
2. With `MEDICORE_DEMO_SEED=true`, a deterministic initializer creates one organization and a stable default branch, then assigns only the known synthetic cohort it owns to that branch using stable business keys.
3. New writes require an active branch immediately.
4. Branch-aware APIs hide or reject unassigned rows; they never silently expose them to every branch.
5. Characterization tests insert an unassigned legacy row and prove it cannot leak through a scoped endpoint.
6. The final three-branch fixture task proves the accepted demo cohort has no null/dangling branch references. A real database migration and unknown-data reconciliation remain behind the Pilot/PostgreSQL gate.

### 4.5 Bed invariants

- Bed status: `AVAILABLE | OCCUPIED | MAINTENANCE | OUT_OF_SERVICE`.
- A bed is unique by `(branchId, ward, room, bedNumber)`.
- `patientId` is not the source of truth on `Bed`; current occupancy is derived from one active admission-bed assignment.
- Only `AVAILABLE` can be assigned. Only an unoccupied bed can enter `MAINTENANCE` or `OUT_OF_SERVICE`.
- Admission creation with a bed and bed occupation happen in one transaction.
- Transfer releases the source and occupies the target in one transaction. Any target conflict rolls back both changes and returns `409`.
- Discharge closes the admission and releases its bed in one transaction. Repeating discharge or transfer after discharge returns `409` with no partial mutation or audit event.
- Repository uniqueness plus optimistic versioning/conditional updates defends against two requests taking the same bed; an in-memory pre-check alone is insufficient.

### 4.6 Staff availability and appointment conflict semantics

- Availability is an explicit dated interval (`startsAt`, `endsAt`) for one staff member in one branch. This avoids inventing recurring-calendar policy in Phase 3.
- `endsAt` must be after `startsAt`; overlapping availability intervals for the same professional are rejected with `409`.
- Appointment duration is explicit (`durationMinutes`) and bounded by validation; no appointment may cross outside one containing availability interval.
- Two non-cancelled appointments for the same professional overlap when `newStart < existingEnd` and `newEnd > existingStart`; overlap returns `409`.
- The check and insert execute transactionally and have a database-backed conflict defense appropriate to the current JPA/H2 training architecture. The plan does not claim distributed locking or production concurrency capacity.
- No rule infers clinical eligibility from profession, license number, or department.

### 4.7 Command-center contracts

- `GET /api/dashboard/branch` returns a typed branch summary for the active branch.
- `GET /api/dashboard/network` returns per-branch summaries and organization totals only for organization-scoped ADMIN assignments.
- Branch summary includes: patient/appointment/admission/emergency/invoice totals already demonstrated; open admissions; active emergency visits; bed counts by status; today's appointments using the server's explicit clock; and simulated invoice status buckets.
- Rejected scheduling requests are not persisted merely to create a chart. They remain visible through failure audit/metrics only if a later explicit contract safely models them; otherwise the dashboard omits that proposed indicator.
- Drill-down links carry UI filter state, but the API still derives branch scope from the token.
- Time-zone policy is not invented. Phase 3 uses UTC instants/ISO timestamps for contracts and documents that branch-local time zones require a later owner decision.

### 4.8 Audit invariants

Every Phase 3 mutation records exactly one successful-command event containing:

- actor username;
- acting assignment ID and role;
- organization and branch ID, plus department ID when applicable;
- action, resource type, resource ID;
- request/correlation ID;
- safe, concise transition details and timestamp.

Failed `400`, `401`, `403`, `404`, and `409` operations create no domain-success event. Audit details never contain bearer tokens, passwords, complete request bodies, stack traces, or sensitive personal fields. Correlation IDs are generated or validated by the server and returned in response headers for evidence; caller-provided values are length/character bounded before use.

## 5. Phase 3 Definition of Done

Phase 3 is accepted only when all outcomes below are proven from a clean checkout:

1. One synthetic organization and three synthetic branches exist with branch-owned departments and unique codes.
2. Identity and acting assignment are separate; an account with several assignments can switch context, while disabled/foreign assignments fail closed.
3. Every migrated branch-scoped list, detail, create, transition, and delete operation rejects cross-branch access server-side; unassigned legacy rows do not leak.
4. The branch selector shows only authorized contexts, switches by receiving a newly issued context-bound token, and clearly displays the current branch and role.
5. A receptionist completes a branch-scoped patient → professional availability → appointment journey; outside-availability and overlapping appointments return `409` without partial writes or success audit events.
6. Beds use normalized DTO/service contracts and legal lifecycle states; duplicate identity, invalid transitions, and assignment of an unavailable bed return `409`.
7. Admission with bed assignment, bed transfer, and discharge/release are atomic and audited exactly once per successful command.
8. Emergency visits and simulated invoices are branch-scoped without changing their non-clinical and no-payment lifecycle boundaries.
9. Branch dashboard results contain only the active branch; network dashboard access is limited to organization-scoped ADMIN and returns typed per-branch comparisons.
10. Audit evidence identifies actor, assignment, role, branch, optional department, action/resource, correlation ID, and timestamp without secrets or request bodies.
11. The opt-in fixture cohort is idempotent and referentially coherent across three branches; a second seed creates zero duplicate records/events and no branch-owned fixture has a null/dangling branch.
12. Frontend unit/integration suites and production build pass; automated browser journeys prove branch switching, responsive layouts, keyboard operation, visible focus, and denial/error states at defined mobile and desktop viewports.
13. The extended multi-branch smoke journey passes against an isolated loopback H2 backend; a dated single-workstation baseline is recorded as a regression tripwire only.
14. API, architecture, traceability, implementation status, runbook, README, screenshots/evidence, and source agree; public files contain no credentials, private addresses, machine paths, real personal data, or unsupported production/scale claims.
15. A formal acceptance review maps all fourteen outcomes above to source, tests, browser/runtime evidence, and documentation, then enforces the final stop gate.

## 6. Dependency-ordered task sequence

Every task is one reviewable branch and one PR. Each implementation PR preserves the full backend suite and frontend suite/build unless the task is backend-only; later tasks never rely on a script or runner before the task that creates it. Product implementation begins only after this plan PR is merged or Mamdouh explicitly authorizes non-blocking stacked execution.

Path actions are relative to the dependency-ordered repository state: every path absent from the current baseline is marked `Create` at its first appearance; a later task correctly marks that same path `Modify` after its creating predecessor has landed.

### Task 1 — Characterize branchless contracts and security boundaries

**Goal:** Pin current global-role, branchless repository, bed CRUD, appointment scheduling, dashboard, audit, auth-response, and frontend-session behavior before changing it.

**Dependencies:** Plan 3 approval only.

| Action | Path |
|---|---|
| Create | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java` |
| Create | `frontend/src/multiBranchContracts.test.js` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/auth/AuthController.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/auth/JwtService.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/auth/JwtFilter.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/auth/UserAccount.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/department/Department.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/bed/BedController.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentService.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardService.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/audit/AuditEvent.java` |
| Read/verify | `frontend/src/auth.js` |
| Read/verify | `frontend/src/api.js` |
| Read/verify | `frontend/src/AppShell.jsx` |

**Contract:** Tests explicitly prove that roles are global, login returns no assignments, data is unscoped, beds accept free-form values, appointments accept overlaps, the dashboard is whole-table, and audit lacks branch/assignment context. Weak behavior is named as a later change target, not normalized in this task.

**Security/audit:** Disposable H2 records and obviously synthetic accounts only. Characterization includes anonymous `401`, denied global-role `403`, and today's absence of cross-branch semantics.

**Acceptance:** Only test files change; focused and full suites pass against the current behavior.

**Run:**

```bash
cd backend
mvn -Dtest=MultiBranchOperationsApiTest,SecurityAuthorizationTest test
mvn test
cd ../frontend
npm test
npm run build
```

**PR:** `test: characterize branchless operations and security baseline`

### Task 2 — Establish organization, branches, and branch-owned departments

**Goal:** Create the single-organization hierarchy and normalize departments without yet pretending the rest of the application is safely branch-scoped.

**Dependencies:** Task 1.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/organization/HospitalOrganization.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/organization/HospitalOrganizationRepository.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/organization/Branch.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/organization/BranchRepository.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/organization/OrganizationDtos.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/organization/OrganizationService.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/organization/OrganizationController.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/department/DepartmentDtos.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/department/DepartmentService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/department/Department.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/department/DepartmentRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/department/DepartmentController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bootstrap/DemoDataInitializer.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/bootstrap/DemoDataInitializerTest.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |

**API/domain contract:**

- `GET /api/organization` returns the single organization and its active branches.
- `POST /api/branches`, `GET /api/branches`, `GET /api/branches/{id}` are ADMIN-only at this stage.
- Branch create request: `{code, name, locationLabel}` with trimmed nonblank values; code uniqueness is enforced inside the organization with pre-check plus DB constraint.
- Departments become DTO/service-owned and require a verified branch. Department code is unique inside a branch.
- No endpoint accepts an organization/branch human code as authorization evidence.

**Legacy strategy:** This task creates the stable synthetic organization/default branch contract and updates the opt-in demo initializer to assign only the known demo-owned department rows using stable business keys. It does not mass-update unknown rows or expose null-branch departments through new scoped queries. `DemoDataInitializerTest` proves that the existing Plan 2 cohort remains idempotent while the hierarchy foundation is introduced.

**Security/audit:** ADMIN-only hierarchy writes; successful creates/updates emit one audit event. Duplicate codes return `409` without an event.

**Acceptance:** Hierarchy and department tests cover referential integrity, uniqueness, DTO allowlists, null/unassigned-row non-disclosure, and idempotent preservation of the prior synthetic cohort; full backend suite passes.

**Run:**

```bash
cd backend
mvn -Dtest=MultiBranchOperationsApiTest,DemoDataInitializerTest test
mvn test
```

**PR:** `feat: establish organization branches and branch-owned departments`

### Task 3 — Add acting assignments and branch-bound authentication context

**Goal:** Separate identity from role/scope and make the selected acting context server-verifiable on every request.

**Dependencies:** Task 2.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/auth/AssignmentScope.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/auth/ActingAssignment.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/auth/ActingAssignmentRepository.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/auth/ActingContext.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/auth/ActingContextService.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/auth/BranchAccessService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/AuthController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/JwtService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/JwtFilter.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/UserAccount.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/UserAccountRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/DevAdminInitializer.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/auth/DevAdminInitializerTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |

**Authentication contract:**

- Login keeps the established credential failure behavior and returns `{accessToken, tokenType, username, roles, assignments, actingContext}` for compatibility during the transition. After assignment adoption, `roles` contains only the selected acting assignment role; the account's legacy global role set is never exposed as cumulative UI authority.
- Each assignment response contains allowlisted IDs/labels, one role, scope, optional branch/department, and enabled state; no entity metadata.
- The initial token is bound to a deterministic enabled assignment. A branch-scoped assignment uses its own branch; an organization-scoped assignment uses a deterministic active branch until the user deliberately switches. If no enabled assignment and eligible active branch exist, authenticated login is refused rather than issuing an unscoped token.
- `POST /api/auth/context` request `{assignmentId, branchId?}` validates that the assignment belongs to the authenticated subject. Branch scope derives its fixed branch; organization scope requires an active branch selection for branch APIs. The response returns a replacement access token and selected context.
- `JwtFilter` reloads the account/assignment/branch and builds one acting authority from server state. Legacy role claims cannot grant access after assignment adoption.

**Security/audit:** Assignment switching is audited without recording the token. Foreign assignment or branch selection is `403`; missing/deleted/disabled context invalidates authentication. Service tests prove header/body tampering cannot widen scope.

**Acceptance:** A multi-assignment synthetic user switches between allowed branches; a foreign assignment, disabled assignment, altered role claim, and deleted branch all fail closed; established login `401` behavior remains.

**Run:**

```bash
cd backend
mvn -Dtest=SecurityAuthorizationTest,MultiBranchOperationsApiTest,DevAdminInitializerTest test
mvn test
```

**PR:** `feat: add acting assignments and branch-bound auth context`

### Task 4 — Scope the first patient and appointment workflow end to end

**Goal:** Prove one complete branch-scoped workflow before broadening to every operations module.

**Dependencies:** Task 3.

| Action | Path |
|---|---|
| Modify | `backend/src/main/java/com/mamtrex/hospital/patient/Patient.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/patient/PatientDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/patient/PatientRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/patient/PatientService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/patient/PatientController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/staff/StaffMember.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/staff/StaffMemberDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/staff/StaffMemberRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/staff/StaffMemberController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/Appointment.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentController.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |

**Contract:** Patient, staff, and appointment create ownership is derived from acting context; lists/details/search resolve only inside the active branch; patient and professional references must share that branch. Cross-branch IDs return `404`. Existing MRN/employee-code uniqueness remains global in Phase 3 unless source constraints are deliberately migrated and fully tested; the plan does not silently weaken it.

**Legacy strategy:** Known seeded patients/staff/appointments are assigned to the stable default branch only under the opt-in demo initializer. Unassigned rows stay inaccessible through branch-scoped endpoints.

**Security/audit:** Server service checks precede writes. Existing role actions remain unless Task 3's assignment matrix narrows them explicitly; UI is not yet the acceptance surface.

**Acceptance:** Two-branch tests prove list/search/detail/write isolation, same-branch reference validation, no success event on failures, and no leakage of an unassigned row.

**Run:**

```bash
cd backend
mvn -Dtest=PatientJourneyApiTest,MultiBranchOperationsApiTest test
mvn test
```

**PR:** `feat: scope patient staff and appointment workflow by branch`

### Task 5 — Deliver the branch-aware shell and context selector

**Goal:** Make assignment and branch context visible and switchable while keeping the server authoritative.

**Dependencies:** Tasks 3–4.

| Action | Path |
|---|---|
| Modify | `frontend/src/api.js` |
| Modify | `frontend/src/auth.js` |
| Modify | `frontend/src/main.jsx` |
| Modify | `frontend/src/LoginPage.jsx` |
| Modify | `frontend/src/LoginPage.test.jsx` |
| Create | `frontend/src/features/branches/actingContextApi.js` |
| Create | `frontend/src/features/branches/BranchSelector.jsx` |
| Create | `frontend/src/features/branches/BranchSelector.test.jsx` |
| Modify | `frontend/src/AppShell.jsx` |
| Modify | `frontend/src/AppShell.test.jsx` |
| Modify | `frontend/src/features/patients/patientApi.js` |
| Modify | `frontend/src/features/patients/PatientsPage.jsx` |
| Modify | `frontend/src/features/patients/PatientsPage.test.jsx` |
| Modify | `frontend/src/features/appointments/appointmentApi.js` |
| Modify | `frontend/src/features/appointments/AppointmentsPage.jsx` |
| Modify | `frontend/src/features/appointments/AppointmentsPage.test.jsx` |
| Modify | `frontend/src/style.css` |

**UI contract:** The shell always displays username, acting role, branch, and optional department. The selector lists only server-returned assignments/branches and calls `/api/auth/context`; successful switching atomically replaces the stored token/context and reloads the selected screen. A `401` clears the session; a `403` preserves login and displays denial; no branch identifier in local/session storage is treated as authority without a matching context-bound token.

**Acceptance:** Component tests cover one assignment, multiple branches, role switching, failed switch rollback, session reload, keyboard labels, and branch-scoped patient/appointment refresh. Backend focused tests are rerun to pin server enforcement.

**Run:**

```bash
cd frontend
npm test
npm run build
cd ../backend
mvn -Dtest=MultiBranchOperationsApiTest,SecurityAuthorizationTest test
mvn test
```

**PR:** `feat: add branch-aware acting-context selector and scoped shell`

### Task 6 — Normalize branch-owned bed inventory

**Goal:** Replace raw bed CRUD with a validated, conflict-safe operational resource.

**Dependencies:** Tasks 2–3.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/bed/BedDtos.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/bed/BedService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bed/Bed.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bed/BedRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bed/BedController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/shared/GlobalExceptionHandler.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |
| Create | `frontend/src/features/beds/bedApi.js` |
| Create | `frontend/src/features/beds/BedsPage.jsx` |
| Create | `frontend/src/features/beds/BedsPage.test.jsx` |
| Modify | `frontend/src/navigation.js` |
| Modify | `frontend/src/navigation.test.js` |
| Modify | `frontend/src/authorization.js` |
| Modify | `frontend/src/authorization.test.js` |
| Modify | `frontend/src/AppShell.jsx` |
| Modify | `frontend/src/AppShell.test.jsx` |
| Modify | `frontend/src/style.css` |

**API/domain contract:**

- `POST /api/beds` accepts `{ward, room, bedNumber}`; branch comes from context and status starts `AVAILABLE`.
- `GET /api/beds`, `GET /api/beds/{id}` return DTOs scoped to the branch.
- `PUT /api/beds/{id}/status` accepts only legal transitions between `AVAILABLE`, `MAINTENANCE`, and `OUT_OF_SERVICE`; `OCCUPIED` is controlled only by admission assignment.
- Duplicate `(branch, ward, room, bedNumber)`, maintenance of an occupied bed, and direct client request for `OCCUPIED` return `409`.
- Delete is allowed only while unoccupied; otherwise `409`.

**Security/audit:** Existing four clinical-administrative roles retain family access initially; exact action narrowing is tested and listed as an owner decision in §8. Every success emits one context-aware event after Task 10; until then the existing event shape remains, without double recording.

**Acceptance:** Backend lifecycle/concurrency/branch-isolation tests and frontend loading/empty/filter/create/status/denial states pass; no raw entity metadata is returned.

**Run:**

```bash
cd backend
mvn -Dtest=MultiBranchOperationsApiTest test
mvn test
cd ../frontend
npm test
npm run build
```

**PR:** `feat: normalize branch-owned bed inventory lifecycle`

### Task 7 — Integrate atomic admission bed assignment, transfer, and release

**Goal:** Make bed occupancy a transactional consequence of the admission lifecycle.

**Dependencies:** Tasks 4 and 6.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionBedAssignment.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionBedAssignmentRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/Admission.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bed/Bed.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bed/BedRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bed/BedService.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |
| Modify | `frontend/src/features/admissions/admissionApi.js` |
| Modify | `frontend/src/features/admissions/AdmissionsPage.jsx` |
| Modify | `frontend/src/features/admissions/AdmissionsPage.test.jsx` |
| Modify | `frontend/src/features/beds/BedsPage.jsx` |
| Modify | `frontend/src/features/beds/BedsPage.test.jsx` |
| Modify | `frontend/src/style.css` |

**API/domain contract:**

- Admission creation adds optional `bedId`; patient and bed must be in the active branch and bed must be `AVAILABLE`.
- `PUT /api/admissions/{id}/bed` request `{bedId}` performs initial assignment or transfer.
- Response DTO adds `branchId` and current bed summary without exposing assignment persistence metadata.
- Discharge transaction stamps `dischargedAt`, closes active assignment, and releases the bed.
- Conditional update/locking and a DB uniqueness rule ensure one active admission per bed and one active bed assignment per admission.

**Failure contract:** Unknown/inaccessible references `404`; unavailable target, duplicate active assignment, illegal lifecycle, or concurrent conflict `409`; any failure rolls back admission/assignment/bed and emits no success event.

**Acceptance:** Tests prove assign, transfer, rollback, discharge release, repeated transition, cross-branch references, and concurrent target conflict. UI shows available beds and updates both admission and bed views after success.

**Run:**

```bash
cd backend
mvn -Dtest=CareOperationsApiTest,MultiBranchOperationsApiTest test
mvn test
cd ../frontend
npm test
npm run build
```

**PR:** `feat: integrate atomic admission bed assignment and release`

### Task 8 — Scope remaining care operations by branch

**Goal:** Prevent emergency and simulated billing workflows from bypassing the new organizational boundary.

**Dependencies:** Tasks 3–4.

| Action | Path |
|---|---|
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisit.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/Invoice.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceController.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |
| Modify | `frontend/src/features/emergency/emergencyApi.js` |
| Modify | `frontend/src/features/emergency/EmergencyVisitsPage.jsx` |
| Modify | `frontend/src/features/emergency/EmergencyVisitsPage.test.jsx` |
| Modify | `frontend/src/features/billing/invoiceApi.js` |
| Modify | `frontend/src/features/billing/InvoicesPage.jsx` |
| Modify | `frontend/src/features/billing/InvoicesPage.test.jsx` |

**Contract:** Branch ownership is server-derived; patient references must be same-branch; lists/details/transitions/deletes are branch-scoped. Existing emergency and invoice status machines, neutral triage label, amount contract, unique invoice number, and ADMIN/BILLING invoice boundary remain unchanged.

**Acceptance:** Cross-branch list/detail/reference/transition/delete tests return the defined denial behavior with no mutations/audit success event; frontend refreshes on context switch and never displays stale rows from the previous branch.

**Run:**

```bash
cd backend
mvn -Dtest=CareOperationsApiTest,MultiBranchOperationsApiTest,SecurityAuthorizationTest test
mvn test
cd ../frontend
npm test
npm run build
```

**PR:** `feat: enforce branch scope across emergency and invoice workflows`

### Task 9 — Add staff availability and appointment conflict prevention

**Goal:** Offer only modeled available times and reject out-of-window or overlapping appointments on the server.

**Dependencies:** Task 4.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/staff/StaffAvailability.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/staff/StaffAvailabilityRepository.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/staff/StaffAvailabilityDtos.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/staff/StaffAvailabilityService.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/staff/StaffAvailabilityController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/Appointment.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentService.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |
| Create | `frontend/src/features/staff/availabilityApi.js` |
| Modify | `frontend/src/features/appointments/AppointmentForm.jsx` |
| Modify | `frontend/src/features/appointments/AppointmentsPage.jsx` |
| Modify | `frontend/src/features/appointments/AppointmentsPage.test.jsx` |
| Modify | `frontend/src/style.css` |

**API/domain contract:**

- `POST /api/staff/{id}/availability` request `{startsAt, endsAt}`; same-branch professional required.
- `GET /api/staff/{id}/availability?from=&to=` returns allowlisted intervals in the active branch.
- Appointment create adds validated `durationMinutes`; server calculates `endsAt` and requires one containing availability interval.
- Overlap rule is half-open intervals: adjacent appointments where one ends exactly when another starts are allowed.
- Cancelled appointment handling follows the existing appointment status contract only after current statuses are characterized; the task must not invent a cancellation lifecycle merely to simplify overlap tests.

**Security/audit:** ADMIN/HR manage availability; ADMIN/RECEPTIONIST schedule. Direct denied API calls are `403`. Availability and appointment successes are audited once; failures are not.

**Acceptance:** Tests cover invalid interval, overlap, adjacency, outside availability, branch mismatch, same-time concurrent requests, and UI selection/error states. No profession-based clinical rule is added.

**Run:**

```bash
cd backend
mvn -Dtest=PatientJourneyApiTest,MultiBranchOperationsApiTest,SecurityAuthorizationTest test
mvn test
cd ../frontend
npm test
npm run build
```

**PR:** `feat: enforce staff availability and appointment conflicts`

### Task 10 — Deliver branch and network command centers with drill-downs

**Goal:** Present operational comparisons without weakening branch scope or claiming production analytics.

**Dependencies:** Tasks 6–9.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardDtos.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/patient/PatientRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/bed/BedRepository.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/reporting/DashboardApiTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |
| Create | `frontend/src/features/dashboard/dashboardApi.js` |
| Modify | `frontend/src/DashboardPage.jsx` |
| Modify | `frontend/src/DashboardPage.test.jsx` |
| Modify | `frontend/src/AppShell.jsx` |
| Modify | `frontend/src/style.css` |

**Contract:** `/api/dashboard/branch` returns a typed summary for the token's active branch. `/api/dashboard/network` returns organization totals plus per-branch summaries only for organization-scoped ADMIN. The old `/api/dashboard` path remains as a temporary branch-summary compatibility alias during Phase 3 and is documented/deprecation-tested; it never falls back to whole-table counts.

**Drill-down:** Cards navigate to already implemented screens with explicit UI filters (status/date) while the backend independently preserves branch context. There is no persisted rejected-conflict counter in this phase unless Task 9 introduced an accepted, privacy-safe data contract; omission is preferred to fabricated analytics.

**Acceptance:** All-zero and populated branch/network responses, cross-branch isolation, ADMIN scope, deterministic response shape, and UI drill-down/filter behavior are pinned. Query count/latency is observed in Task 13, not advertised here.

**Run:**

```bash
cd backend
mvn -Dtest=DashboardApiTest,MultiBranchOperationsApiTest test
mvn test
cd ../frontend
npm test
npm run build
```

**PR:** `feat: add scoped branch and network command centers`

### Task 11 — Enrich audit context and sweep the authorization matrix

**Goal:** Make every sensitive command attributable to identity, acting assignment, branch, and correlation ID, then prove the complete denial matrix.

**Dependencies:** Tasks 3–10.

| Action | Path |
|---|---|
| Modify | `backend/src/main/java/com/mamtrex/hospital/audit/AuditEvent.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/audit/AuditEventRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/audit/AuditService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/audit/AuditController.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/audit/CorrelationIdFilter.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/organization/MultiBranchOperationsApiTest.java` |
| Modify | `frontend/src/features/audit/auditApi.js` |
| Modify | `frontend/src/features/audit/AuditPage.jsx` |
| Modify | `frontend/src/features/audit/AuditPage.test.jsx` |
| Modify | `frontend/src/authorization.js` |
| Modify | `frontend/src/authorization.test.js` |
| Modify | `frontend/src/navigation.js` |
| Modify | `frontend/src/navigation.test.js` |

**Contract:** Audit DTO/filter API supports branch, resource type, actor, and correlation ID. Organization-scoped ADMIN may inspect all branches; branch-scoped ADMIN sees its branch only. Existing events without context remain hidden from branch-scoped views and may be shown to organization ADMIN as `legacy/unassigned` without guessing ownership.

**Authorization sweep:** Pin every implemented action by acting role and scope, including cross-branch `404`, action denial `403`, invalid auth context `401`, and no domain-success event on every failure class. Narrow bed/admission transition roles only if Mamdouh resolves the owner decision in §8 before this task; otherwise preserve and document the current matrix.

**Acceptance:** Exactly-one success events, no failure events, safe detail allowlists, bounded correlation IDs, branch filters, and UI rows pass. A source/publication scan finds no tokens, passwords, request bodies, or private data in fixtures or assertions.

**Run:**

```bash
cd backend
mvn -Dtest=SecurityAuthorizationTest,CareOperationsApiTest,MultiBranchOperationsApiTest test
mvn test
cd ../frontend
npm test
npm run build
```

**PR:** `feat: add assignment and branch context to audit evidence`

### Task 12 — Build the coherent three-branch synthetic cohort

**Goal:** Make every Phase 3 screen and invariant demonstrable with repeatable, referentially valid synthetic data.

**Dependencies:** Tasks 2–11.

| Action | Path |
|---|---|
| Modify | `backend/src/main/java/com/mamtrex/hospital/bootstrap/DemoDataInitializer.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/auth/DevAdminInitializer.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/bootstrap/DemoDataInitializerTest.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/auth/DevAdminInitializerTest.java` |
| Modify | `docs/runbook.md` |

**Fixture contract:** One organization, three obviously synthetic branches of varied size, branch-owned departments, staff, dated availability, beds across all statuses, patients, appointments, admissions/bed assignments, emergency visits, simulated invoices, enabled assignments, and context-aware system audit events. Fixtures use stable business keys and valid same-branch references.

**Legacy proof:** The initializer assigns only records belonging to its known prior synthetic cohort to the default branch, then creates the expanded cohort. A second run inserts zero records and zero events. Tests assert no branch-owned demo row has null/dangling branch ownership and no bed/admission contradiction exists.

**Security/audit:** No credentials are added. Review-account passwords still come only from environment configuration. Synthetic names/domains are unmistakable.

**Acceptance:** Exact first-run composition, referential integrity, branch distribution, dashboard nonzero coverage, and second-run idempotency pass.

**Run:**

```bash
cd backend
mvn -Dtest=DemoDataInitializerTest,DevAdminInitializerTest test
mvn test
```

**PR:** `feat: seed an idempotent three-branch operations cohort`

### Task 13 — Add responsive, accessible browser evidence

**Goal:** Prove the branch-aware Portfolio journey in a real browser at mobile and desktop viewports instead of relying only on component tests.

**Dependencies:** Tasks 5–12.

| Action | Path |
|---|---|
| Modify | `frontend/package.json` |
| Modify | `frontend/package-lock.json` |
| Create | `frontend/playwright.config.js` |
| Create | `frontend/e2e/multi-branch-journey.spec.js` |
| Modify | `frontend/src/AppShell.jsx` |
| Modify | `frontend/src/AppShell.test.jsx` |
| Modify | `frontend/src/style.css` |
| Modify | `docs/runbook.md` |
| Create | `docs/evidence/phase3/README.md` |
| Create | `docs/evidence/phase3/branch-command-center-mobile.png` |
| Create | `docs/evidence/phase3/branch-command-center-desktop.png` |
| Create | `docs/evidence/phase3/bed-transfer-mobile.png` |

**Runner contract:** Add an explicit `test:e2e` script using a pinned Playwright version. The test starts or targets only the disposable review frontend/backend documented in `docs/runbook.md`, uses synthetic fixtures, and verifies assignment/branch switching, branch isolation, appointment conflict messaging, bed assignment/transfer/discharge, command-center drill-down, and audit evidence. The runbook records dependency/browser installation, environment-variable names, startup order, loopback endpoints, and cleanup without embedding credential values.

**Accessibility/responsiveness:** Define tested desktop and narrow mobile viewports; no horizontal page overflow; branch context remains visible; navigation remains operable; controls have accessible names; keyboard focus is visible; dialogs/forms return focus predictably; status is not conveyed by color alone. Automated checks supplement, not replace, direct visual inspection.

**Evidence:** Screenshots are created only by the passing real-browser run and documented with revision, viewport, journey step, and synthetic-data boundary. Failed or unverified images do not ship.

**Acceptance:** Unit/integration tests, production build, and browser tests pass; screenshots are inspected; no console error, clipped core action, inaccessible selector, or stale-branch content is observed.

**Run:**

```bash
cd frontend
npm test
npm run build
npm run test:e2e
```

**PR:** `test: add responsive multi-branch browser evidence`

### Task 14 — Extend the smoke journey and record a regression baseline

**Goal:** Exercise the complete Phase 3 HTTP journey repeatedly and record measured evidence without making scale claims.

**Dependencies:** Tasks 3–13.

| Action | Path |
|---|---|
| Modify | `scripts/smoke-patient-journey.sh` |
| Modify | `docs/performance.md` |

**Journey:** Login with an assigned synthetic account → switch context → create/search patient → read staff availability → create appointment → assert overlap `409` → create/assign bed → admit → transfer bed → discharge/release → emergency transitions → simulated invoice transitions → branch dashboard → organization network dashboard with organization ADMIN → filtered audit evidence → switch branch and prove prior branch rows are absent.

**Security/runtime:** The script requires credentials from environment, never prints tokens/bodies, and only targets a disposable local/ephemeral backend. It verifies branch-bound claims through observable API behavior, not by decoding and trusting the token. Runtime is loopback-only and cleaned after supervisor measurement.

**Acceptance:** Script fails on any wrong status, missing field, branch leakage, partial bed mutation, absent audit context, or dashboard mismatch. A dated repeated isolated run records per-step min/median/max and total duration as a single-workstation regression tripwire; no SLA, concurrency, capacity, or production claim.

**Run:**

```bash
bash -n scripts/smoke-patient-journey.sh
cd backend
mvn test
cd ../frontend
npm test
npm run build
```

A live measurement follows `docs/runbook.md` using a disposable loopback backend and environment-only secrets.

**PR:** `test: extend smoke evidence across multi-branch operations`

### Task 15 — Publish Phase 3 architecture, evidence, and formal stop gate

**Goal:** Document exactly what the accepted implementation demonstrates and stop before any later phase.

**Dependencies:** Tasks 1–14.

| Action | Path |
|---|---|
| Create | `docs/multi-branch-operations.md` |
| Create | `docs/architecture/multi-branch-operations.md` |
| Modify | `docs/api.md` |
| Modify | `docs/traceability.md` |
| Modify | `docs/implementation-status.md` |
| Modify | `docs/patient-journey.md` |
| Modify | `docs/care-operations.md` |
| Modify | `docs/runbook.md` |
| Modify | `README.md` |

**Documentation contract:** Document only source-verified hierarchy, acting-context, branch scope, bed, availability, command-center, audit, fixture, browser, and smoke behavior. Architecture diagrams show UI → API adapter → controller/DTO → scoped service/use case → repository, with acting context and audit as cross-cutting adapters—not framework imports inside domain policy.

**Formal acceptance:** Map every §5 outcome to exact source, backend/frontend/browser tests, screenshots, live smoke, and documentation. Run all suites and the smoke from a clean checkout using only repository instructions. Audit links, paths, commands, public privacy, and unsupported claims.

**Acceptance:** Every §5 row is `PASS` with fresh evidence or the phase remains `PARTIAL/BLOCKED`. Documentation explicitly repeats that this is a synthetic Training/Portfolio single-organization multi-branch demonstration, not multi-tenancy, Pilot, clinical use, production readiness, or measured capacity.

**Run:**

```bash
cd backend
mvn test
cd ../frontend
npm test
npm run build
npm run test:e2e
cd ..
bash -n scripts/smoke-patient-journey.sh
git diff --check
```

The final live smoke uses the disposable runtime procedure in `docs/runbook.md` and is not replaced by script syntax validation.

**PR:** `docs: publish multi-branch operations portfolio evidence`

## 7. PR map and merge order

| Order | Task | Intended PR |
|---|---|---|
| 1 | Characterization | `test: characterize branchless operations and security baseline` |
| 2 | Organization hierarchy | `feat: establish organization branches and branch-owned departments` |
| 3 | Assignments/auth context | `feat: add acting assignments and branch-bound auth context` |
| 4 | First scoped workflow | `feat: scope patient staff and appointment workflow by branch` |
| 5 | Branch-aware shell | `feat: add branch-aware acting-context selector and scoped shell` |
| 6 | Bed inventory | `feat: normalize branch-owned bed inventory lifecycle` |
| 7 | Admission/bed integration | `feat: integrate atomic admission bed assignment and release` |
| 8 | Remaining care operations | `feat: enforce branch scope across emergency and invoice workflows` |
| 9 | Availability/conflicts | `feat: enforce staff availability and appointment conflicts` |
| 10 | Command centers | `feat: add scoped branch and network command centers` |
| 11 | Audit/RBAC sweep | `feat: add assignment and branch context to audit evidence` |
| 12 | Fixtures | `feat: seed an idempotent three-branch operations cohort` |
| 13 | Browser evidence | `test: add responsive multi-branch browser evidence` |
| 14 | Smoke/performance | `test: extend smoke evidence across multi-branch operations` |
| 15 | Documentation/gate | `docs: publish multi-branch operations portfolio evidence` |

The default order is strict because scope and authorization foundations precede resources, resources precede aggregation/evidence, and evidence precedes acceptance. If Mamdouh authorizes non-blocking execution, later branches may stack from the verified predecessor tip; each PR remains separate, states its dependency, and is merged in this order.

## 8. Risks and owner decisions

These choices are not silently invented during implementation:

1. **Operational write-role narrowing:** Current admissions/emergency/beds family rules allow ADMIN, DOCTOR, NURSE, and RECEPTIONIST on all methods. Recommended future matrix: RECEPTIONIST creates admissions, NURSE/ADMIN assigns or transfers beds, and ADMIN/DOCTOR discharges. This changes authority and requires Mamdouh's explicit selection before Tasks 6–7; otherwise Phase 3 preserves the existing roles while adding branch scope.
2. **Organization administrator behavior:** This plan allows organization-scoped ADMIN to compare branches and choose a branch-bound context. It does not authorize ordinary organization-wide patient listing. Any cross-branch patient-search requirement is a separate privacy/UX decision.
3. **Patient ownership:** Phase 3 assigns each demonstrated patient record to one branch. A shared organization-wide identity with branch registrations would be a different data model and is deferred; this plan does not imply cross-branch clinical record exchange.
4. **Time zones:** Contracts use ISO timestamps and server UTC. Branch-local time zones, daylight-saving behavior, and appointment display conversion require an explicit policy before Pilot work.
5. **Appointment duration:** A bounded duration field is required for overlap semantics, but exact allowed minimum/maximum values must be selected as technical validation constants and documented before Task 9; they are not clinical policy.
6. **Legacy data:** Automatic reassignment is limited to known synthetic seed records. Unknown branchless rows stay inaccessible. Real-data reconciliation/migration is forbidden in this phase.
7. **Concurrency evidence:** Transactional and database defenses are tested, but H2/local tests do not establish distributed or production concurrency capacity.
8. **Browser evidence storage:** Screenshots may be committed only when they contain synthetic data and no private address, token, username tied to a real person, or local machine path.

## 9. Verification policy

Each PR must provide:

- pre-change baseline and exact changed-path ledger;
- failing characterization/behavior test before implementation where behavior changes;
- focused tests, full backend suite, full frontend suite/build as applicable;
- direct behavior evidence for authorization, cross-branch isolation, transaction rollback, and UI context switching;
- scoped `git diff --check`;
- staged public-content scan before publication;
- no commit, push, PR, or merge by an implementation worker;
- independent acceptance with verdict `PASS`, `PARTIAL`, or `BLOCKED`.

A green test suite does not waive a task's stated completion gate. Cross-branch leakage, a client-trusted scope value, a partial bed mutation, an unaudited success, or a success event on failure blocks publication even if unrelated tests pass.

## 10. Stop gate

After Task 15, perform the formal Training/Portfolio acceptance review against §5 and then stop.

Phase 3 acceptance authorizes only a synthetic, single-organization, multi-branch Portfolio demonstration. It does **not** authorize:

- a Pilot or a later phase;
- real patient, hospital, employee, financial, or operational data;
- SaaS tenancy or a tenant-isolation claim;
- clinical protocols, medical advice, decision support, certification, or clinical integrations;
- payment, insurer, tax, FX, refund, or collection integrations;
- PostgreSQL migration rehearsal, production deployment, production hardening, or production-readiness/capacity claims.

Any such escalation requires Mamdouh's explicit, separate decision after the Phase 3 evidence is reviewed.
