# MediCore — Phase 2 Plan: Care Lifecycle & Operations (Training/Portfolio)

> **Status:** Approved planning target for the next Training/Portfolio milestone. This document plans implementation; it does not claim that the listed work is already done.
>
> **Boundary:** MediCore is an educational, non-clinical training project — never certified medical software, never a production hospital system, never a source of medical advice. Everything in this phase is administrative workflow simulation over synthetic data.

---

## 1. Phase mission

Phase 1 (docs/plan1.md) delivered one coherent patient journey: login, patient registration, search/detail/edit, appointment scheduling against verified references, role-aware navigation, server-enforced RBAC, ADMIN-visible audit, opt-in synthetic demo data, a repeatable smoke script, and portfolio documentation.

Phase 2 completes the operational journey **after** a patient appointment: admissions, emergency visits, and invoices become real, validated workflows with status lifecycles, and the dashboard makes those flows visible to the user. The existing Phase 1 journey is preserved as the foundation, not reworked.

The phase stays educational and non-clinical: triage labels, bed state, and money amounts are demo simulations, not clinical assessments or real financial processing.

## 2. Truthful current baseline (verified against source)

**Delivered in Phase 1 (all plan1 Tasks 1–13 implemented, per `docs/implementation-status.md`):**

- Patient journey: normalized DTO contracts (`PatientDtos`, `AppointmentDtos`, `StaffMemberDtos`), service-owned rules (`PatientService`, `AppointmentService`), verified UUID references, stable `ApiError` mapping via `GlobalExceptionHandler` (404/400/409).
- Frontend: `AppShell` in-memory navigation, Patients/Appointments/Audit screens, `api.js` transport (`apiFetch`, `ApiError`, session-expiry callback), `authorization.js` permission map mirroring the server matrix.
- Server RBAC in `SecurityConfig`: method-level write rules for patients/appointments; family rules for everything else; ADMIN-only catch-all; 401 vs 403 distinction.
- Audit: `AuditService.record(action, type, id, details)` with session actor (or `system`); ADMIN-only `GET /api/audit`; frontend `AuditPage`.
- Demo data: opt-in idempotent seeder (`DemoDataInitializer`, `MEDICORE_DEMO_SEED`) covering patients, staff, appointments, with system-actor audit events.
- Evidence: `PatientJourneyApiTest`, `SecurityAuthorizationTest`, `DemoDataInitializerTest`, Vitest/RTL frontend suite, `scripts/smoke-patient-journey.sh`, dated baseline in `docs/performance.md`.

**Care-operations modules exist but are raw CRUD (the Phase 2 gap):**

| Module | Endpoint | Current state |
|---|---|---|
| Admissions | `/api/admissions` | `AdmissionController` returns the JPA entity directly; `Admission` fields (`patientId`, `admittedAt`, `dischargedAt`, `reason`, `status`) are all raw `String`s; `patientId` is never verified; create/list/get/delete only — **no update or discharge transition**; audit only on CREATE/DELETE; no service layer, no DTO. |
| Emergency visits | `/api/emergency-visits` | `EmergencyVisitController`, same raw shape; fields (`patientId`, `arrivalAt`, `triageLevel`, `chiefComplaint`, `status`) all `String`s; unverified `patientId`; no status-transition endpoint. |
| Invoices | `/api/invoices` | `InvoiceController`, same raw shape; fields (`patientId`, `invoiceNumber`, `amount`, `currency`, `status`) all `String`s; no uniqueness on `invoiceNumber`; no lifecycle; **RBAC: ADMIN/BILLING only** — RECEPTIONIST, DOCTOR, NURSE get `403` on every invoice route. |
| Beds | `/api/beds` | Same raw shape; explicitly **out of scope** for Phase 2 (see §3). |
| Dashboard | `/api/dashboard` | `DashboardController.summary()` returns raw counts only: `patients`, `appointments`, `admissions`, `emergencyVisits`, `invoices`. No status awareness; any authenticated role. |

**Other verified facts this plan relies on:**

- `SecurityConfig` family rules: `/api/admissions/**`, `/api/beds/**`, `/api/emergency-visits/**` → ADMIN, DOCTOR, NURSE, RECEPTIONIST (all methods); `/api/invoices/**` → ADMIN, BILLING; `/api/dashboard/**` and `/api/notifications/**` → any authenticated role; `/api/audit/**` → ADMIN only.
- `Role` enum: ADMIN, DOCTOR, NURSE, RECEPTIONIST, LAB_TECH, RADIOLOGY_TECH, PHARMACIST, BILLING, HR, STAFF.
- No automated test currently touches the admissions, emergency, invoice, bed, or dashboard endpoints; `DashboardPage.jsx` has no frontend test and renders stat entries generically from the response map.
- Frontend destinations today: Dashboard, Patients, Appointments, Audit — no admissions, emergency, or invoice screens exist.
- `frontend/src/DashboardPage.test.jsx`, every path marked **Create** in this plan, and the per-domain service/DTO classes for admissions/emergency/billing do **not** exist yet.
- `BaseEntity` supplies `id` (UUID), `createdAt`, `updatedAt`, `version`; entity-direct responses therefore currently expose persistence metadata. The shared `RecordStatus` enum exists (`DRAFT, ACTIVE, COMPLETED, CANCELLED, ARCHIVED`) but Phase 2 follows the appointment-contract precedent of explicit per-domain status sets bound with `@Pattern`.

## 3. Scope, non-goals, and boundaries

### In scope

- Admission workflow: verified patient reference, server-owned lifecycle (admit → discharge), DTO contracts, frontend screen and cross-links.
- Emergency-visit workflow: verified patient reference, non-clinical triage label, status transitions, frontend screen.
- Invoice lifecycle: verified patient reference, unique invoice number, simulated amount/currency, status transitions, frontend screen — explicitly financial **simulation**.
- Dashboard aggregation over the new statuses and user-facing visibility with labels.
- Role-aware navigation and frontend flows mirroring the authoritative server RBAC (including the ADMIN/BILLING-only invoice boundary).
- Audit coverage and failure/error contracts for every new mutation.
- Synthetic fixture expansion only where needed to exercise the new flows.
- Focused/full tests, extended smoke/performance evidence, documentation, and portfolio evidence.

### Explicit non-goals

- Pilot, real patient or hospital data, production hardening claims, certification claims, medical advice, or clinical decision support.
- Payment gateways, real money movement, tax/regulatory policy, refunds, credit notes, or the `/api/insurance-claims` family.
- Bed management (`/api/beds`) UI or admission–bed linking — deferred pending an owner decision (§7).
- Any real triage protocol (ATS/ESI/MTS or similar). The triage field stays a neutral `1–5` demo label with no clinical meaning.
- Docker, PostgreSQL migration rehearsal, multi-tenancy, HL7/FHIR, microservices.
- Schema migration of legacy String-typed columns: new contracts use typed requests resolved to canonical strings, exactly like the Phase 1 appointment precedent; no destructive column migration.
- Inventing hospital policy: status sets and transition rules are explicit, minimal API contracts — not claims about real hospital procedure.

### Operational/review support is not feature backlog

Account provisioning and the current runtime synthetic data are **operational/review support**, distinct from product tasks: `DevAdminInitializer` (admin bootstrap via `HOSPITAL_ADMIN_PASSWORD`; opt-in review accounts via `MEDICORE_REVIEW_ACCOUNTS_ENABLED`), the `MEDICORE_DEMO_SEED` flag, and the local review steps in `docs/runbook.md`. Only Task 8 touches the seeder, because the new flows need coherent fixtures; provisioning itself stays out of the backlog.

## 4. Phase 2 Definition of Done

The phase is complete only when all of the following are proven:

1. An authorized user can register an admission for an existing synthetic patient, see it in the admissions list/detail, and discharge it — discharge time is stamped by the server, and the full flow is reference-verified, validated, and audited.
2. An authorized user can register an emergency visit (arrival time, `1–5` triage label, synthetic chief complaint) and move it through `WAITING → IN_TREATMENT → CLOSED` transitions.
3. An ADMIN or BILLING user can create an invoice with a unique invoice number and simulated amount/currency and move it through `DRAFT → ISSUED → PAID` (or `VOID`); duplicates return `409`; no payment integration exists or is implied.
4. The dashboard shows the new status-aware aggregates next to the existing counts, with human-readable labels in the frontend; the smoke script asserts the extended contract.
5. Navigation offers Admissions and Emergency Visits to the four clinical-administrative roles and Invoices only to ADMIN and BILLING — and direct API calls from denied roles return `403`, pinned by tests.
6. Every new successful mutation produces exactly one audit event with the acting user; failed operations produce none; the ADMIN audit screen surfaces the new resource types.
7. Demo fixtures exercise the new flows: opt-in, idempotent, coherent with the existing seeded patients/staff/appointments, with system-actor audit events on first seed only.
8. Backend suite, frontend suite, frontend build, and the extended smoke script all pass; a new dated baseline is recorded in `docs/performance.md` with no capacity claim.
9. `docs/api.md`, `docs/traceability.md`, `docs/implementation-status.md`, `docs/runbook.md`, `README.md`, and the journey/architecture docs describe only verified behavior; `git diff --check` is clean.
10. The non-clinical boundary statement is unchanged everywhere.

**Owner gate:** Plan2 acceptance authorizes Training/Portfolio work only. Escalation toward Pilot, real data, payment/insurer integration, real triage protocols, or clinical-adjacent behavior requires Mamdouh's explicit later approval and is out of scope for every task below.

## 5. Dependency-ordered task sequence

Each task is one independently deliverable vertical slice on its own branch → PR → merge. Every PR keeps the full suite green: `cd backend && mvn test` plus `cd frontend && npm test && npm run build`.

### Task 1 — Characterize care-operations contracts and authorization

**Goal:** Freeze today's raw admission, emergency, invoice, bed, and dashboard behavior in integration tests before any write task changes it, including the RBAC facts (BILLING-only invoices, four-role admissions/emergency access, entity responses exposing persistence metadata, unverified patient identifiers).

**Dependencies:** none (first task).

| Action | Path |
|---|---|
| Create | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionController.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitController.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceController.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/bed/BedController.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardController.java` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java` |
| Read/verify (precedent) | `backend/src/test/java/com/mamtrex/hospital/patient/PatientJourneyApiTest.java` |

**Key contract:** assertions pin (a) current entity-shaped responses including `createdAt`/`updatedAt`/`version`; (b) acceptance of arbitrary string values (statuses, dates, amounts) and of a nonexistent `patientId`; (c) dashboard count keys; (d) the authorization matrix — anonymous `401`, BILLING allowed on `/api/invoices/**` while RECEPTIONIST/DOCTOR/NURSE get `403`, four-role access on admissions/emergency, dashboard for any authenticated role.

**Security/audit:** disposable in-memory H2 accounts and synthetic records only (established test pattern); no real personal data; assert create/delete audit events exist today so later tasks can be held to the same bar.

**Acceptance:** the characterization test passes against current behavior with zero production changes (`git diff` shows only the new test); weak behaviors are named in comments as intentional later change targets.

**Run:** `cd backend && mvn -Dtest=CareOperationsApiTest test`

### Task 2 — Admissions workflow (vertical slice)

**Goal:** A user registers an admission for a verified patient and later discharges it. Backend contract normalized; frontend screen, destination, and cross-link delivered in the same PR.

**Dependencies:** Task 1.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionDtos.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionController.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/shared/InvalidStateTransitionException.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/shared/GlobalExceptionHandler.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Create | `frontend/src/features/admissions/admissionApi.js` |
| Create | `frontend/src/features/admissions/AdmissionsPage.jsx` |
| Create | `frontend/src/features/admissions/AdmissionsPage.test.jsx` |
| Modify | `frontend/src/navigation.js` |
| Create | `frontend/src/navigation.test.js` |
| Modify | `frontend/src/authorization.js` |
| Modify | `frontend/src/authorization.test.js` |
| Modify | `frontend/src/AppShell.jsx` |
| Modify | `frontend/src/AppShell.test.jsx` |
| Modify | `frontend/src/features/patients/PatientDetailPage.jsx` |
| Modify | `frontend/src/style.css` |
| Read | `frontend/src/features/patients/patientApi.js` |
| Read (pattern precedent) | `backend/src/main/java/com/mamtrex/hospital/appointment/AppointmentDtos.java` |

**Key contract:**

- `POST /api/admissions` — `{patientId: UUID (must resolve or 404), admittedAt: ISO LocalDateTime, reason: @NotBlank}`; server sets `status=ADMITTED`; `patientId` stored as the canonical patient UUID string (appointment precedent, no column migration). Response DTO `{id, patientId, admittedAt, dischargedAt, reason, status}` — no persistence metadata.
- `GET /api/admissions`, `GET /api/admissions/{id}` — DTO list/detail; DELETE remains service-owned and audited.
- `PUT /api/admissions/{id}/status` — `{status: "DISCHARGED"}` only valid from `ADMITTED`; the server stamps `dischargedAt`; invalid transitions return `409` via the new shared `InvalidStateTransitionException` mapped in `GlobalExceptionHandler`; status set is `ADMITTED | DISCHARGED`.
- Frontend: admissions destination (roles = server family rule: ADMIN, DOCTOR, NURSE, RECEPTIONIST), list with status badges, register form with patient selection via the existing `patientApi`, discharge action with confirmation, "Register admission" entry point from `PatientDetailPage`; loading/empty/error/`401`/`403` states per the established page conventions; components never call `fetch` directly.

**Security/audit:** server RBAC unchanged (family rule); audit `CREATE` on admit, `UPDATE` ("discharged") on transition, `DELETE` on delete — actor from session; failed creates (404/400) produce no audit event. UI role filtering mirrors `SecurityConfig` and never authorizes.

**Acceptance:** invalid/missing patient → documented client error; discharge of an already-discharged admission → `409`; audit events present for admit/discharge; frontend tests cover list, form validation, discharge, and denial states; full suites green.

**Run:** `cd frontend && npm test && npm run build && cd ../backend && mvn -Dtest=CareOperationsApiTest test && mvn test`

### Task 3 — Emergency-visit workflow (vertical slice)

**Goal:** Register an emergency visit with a non-clinical triage label and move it through status transitions. Same slice shape as Task 2.

**Dependencies:** Task 2 (reuses `InvalidStateTransitionException` and the established frontend patterns).

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitDtos.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitController.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Create | `frontend/src/features/emergency/emergencyApi.js` |
| Create | `frontend/src/features/emergency/EmergencyVisitsPage.jsx` |
| Create | `frontend/src/features/emergency/EmergencyVisitsPage.test.jsx` |
| Modify | `frontend/src/navigation.js` |
| Modify | `frontend/src/authorization.js` |
| Modify | `frontend/src/authorization.test.js` |
| Modify | `frontend/src/AppShell.jsx` |
| Modify | `frontend/src/AppShell.test.jsx` |
| Modify | `frontend/src/style.css` |

**Key contract:**

- `POST /api/emergency-visits` — `{patientId: UUID (must resolve or 404), arrivalAt: ISO LocalDateTime, triageLevel: @Pattern("1|2|3|4|5"), chiefComplaint: @NotBlank}`; server sets `status=WAITING`. The triage label is documented in-code and in `docs/api.md` as a demo classification with **no clinical meaning**.
- `PUT /api/emergency-visits/{id}/status` — allowed transitions `WAITING → IN_TREATMENT | CLOSED`, `IN_TREATMENT → CLOSED`; `CLOSED` is terminal; anything else `409`. Status set: `WAITING | IN_TREATMENT | CLOSED`.
- `GET` list/detail as DTOs; delete stays service-owned and audited.
- Frontend: emergency destination (same four roles), list with status/triage columns, register form (patient selection, arrival time, triage select, synthetic complaint text), transition actions; an "Admit" convenience link from a visit that prefills the admissions form is UI-only sugar and creates no cross-module coupling.

**Security/audit:** RBAC unchanged (family rule, all four roles); audit `CREATE` and every `UPDATE` transition; failures create nothing.

**Acceptance:** non-`1–5` triage → `400`; unknown patient → `404`; illegal transition → `409`; frontend tests cover the matrix of states; suites green.

**Run:** same combined command as Task 2 (`npm test`, `npm run build`, `mvn -Dtest=CareOperationsApiTest test`, `mvn test`).

### Task 4 — Invoice lifecycle (vertical slice, financial simulation)

**Goal:** ADMIN/BILLING create invoices with unique numbers and simulated amounts, then move them through a small explicit lifecycle. No payment integration of any kind.

**Dependencies:** Task 2 (shared transition exception), Task 3 not required but the PR follows it for review order.

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceDtos.java` |
| Create | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceService.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/Invoice.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Create | `frontend/src/features/billing/invoiceApi.js` |
| Create | `frontend/src/features/billing/InvoicesPage.jsx` |
| Create | `frontend/src/features/billing/InvoicesPage.test.jsx` |
| Modify | `frontend/src/navigation.js` |
| Modify | `frontend/src/authorization.js` |
| Modify | `frontend/src/authorization.test.js` |
| Modify | `frontend/src/AppShell.jsx` |
| Modify | `frontend/src/AppShell.test.jsx` |
| Modify | `frontend/src/style.css` |

**Key contract:**

- `POST /api/invoices` — `{patientId: UUID (must resolve or 404), invoiceNumber: @NotBlank (unique), amount: @NotNull @DecimalMin("0.00") @Digits(integer=12, fraction=2) BigDecimal, currency: @Pattern("[A-Z]{3}")}`; server sets `status=DRAFT`; amount persisted in canonical plain-string form (no column-type migration). Duplicate `invoiceNumber` → `409` via the Phase 1 duplicate-MRN pattern (service pre-check plus DB unique constraint; SQL internals never leak).
- `PUT /api/invoices/{id}/status` — allowed transitions `DRAFT → ISSUED | VOID`, `ISSUED → PAID | VOID`; `PAID`/`VOID` terminal; otherwise `409`. Status set: `DRAFT | ISSUED | PAID | VOID`. Response echoes the stored canonical amount as a string; currency is a demo label — no conversion, no FX, no tax.
- Frontend: invoices destination offered only to ADMIN and BILLING (server denies everyone else with `403`); list with status badges; create form (patient selection, invoice number, amount, currency); transition actions; page copy states "financial simulation — no real payments".

**Security/audit:** server RBAC unchanged (ADMIN/BILLING); audit `CREATE` and every transition; failures produce nothing; the UI boundary mirrors the server and is never treated as authorization.

**Acceptance:** duplicate invoice number → `409` without overwriting; negative amount → `400`; transitions validated; RECEPTIONIST direct API read → `403` (pin extends Task 1's characterization); suites green.

**Run:** same combined command as Task 2 (`npm test`, `npm run build`, `mvn -Dtest=CareOperationsApiTest test`, `mvn test`).

### Task 5 — Status-aware operations dashboard (vertical slice)

**Goal:** The dashboard reflects the new lifecycles, and the frontend presents the numbers with labels. Existing keys are preserved for backward compatibility.

**Dependencies:** Tasks 2–4 (statuses must exist to aggregate).

| Action | Path |
|---|---|
| Create | `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardService.java` |
| Create | `backend/src/test/java/com/mamtrex/hospital/reporting/DashboardApiTest.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/reporting/DashboardController.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/admission/AdmissionRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/emergency/EmergencyVisitRepository.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/billing/InvoiceRepository.java` |
| Modify | `frontend/src/DashboardPage.jsx` |
| Create | `frontend/src/DashboardPage.test.jsx` |
| Modify | `frontend/src/style.css` |

**Key contract:** `GET /api/dashboard` keeps `patients`, `appointments`, `admissions`, `emergencyVisits`, `invoices` and adds flat count keys: `openAdmissions` (status `ADMITTED`), `activeEmergencyVisits` (`WAITING | IN_TREATMENT`), `invoicesDraft`, `invoicesIssued`, `invoicesPaid`, `invoicesVoid`. Repositories expose derived `countByStatus(String)` queries; a `DashboardService` owns aggregation so the controller stays a thin mapper (Phase 1 layering precedent). Frontend groups the keys into labeled sections (current activity / totals / invoices by status) and keeps the generic fallback for unknown keys.

**Security/audit:** endpoint stays any-authenticated-role (server rule unchanged); read-only — no audit implications.

**Acceptance:** `DashboardApiTest` pins every key including all-zero cases; `DashboardPage.test.jsx` is the first dashboard frontend test (loading, error, labeled groups); smoke compatibility of the five original keys is not broken; suites green.

**Run:** `cd backend && mvn -Dtest=DashboardApiTest test && mvn test && cd ../frontend && npm test && npm run build`

### Task 6 — Role-aware navigation and RBAC matrix alignment

**Goal:** Prove, end to end, that what the UI offers and what the server enforces agree across the whole care-operations surface — including the cross-module asymmetries (BILLING sees invoices but not admissions; RECEPTIONIST sees admissions but not invoices).

**Dependencies:** Tasks 2–5.

| Action | Path |
|---|---|
| Modify | `backend/src/test/java/com/mamtrex/hospital/auth/SecurityAuthorizationTest.java` |
| Modify | `frontend/src/authorization.js` |
| Modify | `frontend/src/authorization.test.js` |
| Modify | `frontend/src/navigation.js` |
| Modify | `frontend/src/navigation.test.js` |
| Modify (if flow gaps surface) | `frontend/src/features/admissions/AdmissionsPage.jsx` |
| Modify (if flow gaps surface) | `frontend/src/features/emergency/EmergencyVisitsPage.jsx` |
| Modify (if flow gaps surface) | `frontend/src/features/billing/InvoicesPage.jsx` |

**Key contract:** the permission map gains `admission`, `emergencyVisit`, and `invoice` resources (read/create/transition as implemented); `navigation.test.js` (created by Task 2 alongside the first destination change if the existing `AppShell.test.jsx` does not already cover destination sets) pins the per-role destination lists; `SecurityAuthorizationTest` gains the full matrix: ADMIN everywhere, DOCTOR/NURSE admissions+emergency but `403` invoices, RECEPTIONIST admissions+emergency but `403` invoices, BILLING invoices but `403` admissions/emergency/patients, anonymous `401` everywhere. If `navigation.test.js` already exists by then, this task extends it instead of creating it.

**Security/audit:** test-only alignment sweep; any production-file change it flushes out must preserve deny-by-default server enforcement (UI hiding is convenience, never authorization). No behavior widening without an owner decision (§7).

**Acceptance:** the documented matrix in `docs/api.md` terms matches the enforced rules; direct API calls from every denied role return `403`; `401` still returns the user to login while `403` keeps the session.

**Run:** `cd backend && mvn -Dtest=SecurityAuthorizationTest test && mvn test && cd ../frontend && npm test && npm run build`

### Task 7 — Audit coverage and failure/error contract sweep

**Goal:** Close the evidence loop: every new mutation audited exactly once, every failure contract pinned, and the ADMIN audit screen proven to surface the new resource types.

**Dependencies:** Tasks 2–6.

| Action | Path |
|---|---|
| Modify | `backend/src/test/java/com/mamtrex/hospital/operations/CareOperationsApiTest.java` |
| Modify | `backend/src/main/java/com/mamtrex/hospital/audit/AuditService.java` (only if the sweep exposes a real gap) |
| Modify | `frontend/src/features/audit/AuditPage.test.jsx` |
| Read/verify | `backend/src/main/java/com/mamtrex/hospital/audit/AuditController.java` |
| Read/verify | `frontend/src/features/audit/AuditPage.jsx` |

**Key contract:** pins — admit/discharge, emergency transitions, and invoice create/transitions each produce exactly one audit event with the acting username; failed operations (`404` unknown reference, `400` validation, `409` duplicate/illegal transition) produce none; the details payload names the transition (e.g., `status: DISCHARGED`); the audit event shape (`actor`, `action`, `resourceType`, `resourceId`, `details`, `occurredAt`) is unchanged; `AuditPage.test.jsx` extends to render an admission/invoice event row without exposing metadata beyond the existing five evidence fields.

**Security/audit:** this task is the audit gate itself; no tokens, credentials, or request bodies may appear in any event or test fixture.

**Acceptance:** the sweep passes with (at most) minimal service fixes it directly justifies; suites green.

**Run:** `cd backend && mvn -Dtest=CareOperationsApiTest test && mvn test && cd ../frontend && npm test && npm run build`

### Task 8 — Synthetic fixture expansion (only where the new flows need it)

**Goal:** The opt-in demo cohort gains coherent admissions, emergency visits, and invoices so the new screens, dashboard aggregates, and smoke steps have data — without breaking idempotency or the existing seed.

**Dependencies:** Tasks 2–5 (the contracts being seeded).

| Action | Path |
|---|---|
| Modify | `backend/src/main/java/com/mamtrex/hospital/bootstrap/DemoDataInitializer.java` |
| Modify | `backend/src/test/java/com/mamtrex/hospital/bootstrap/DemoDataInitializerTest.java` |
| Modify | `docs/runbook.md` (cohort description only) |

**Key contract:** seeded records reference existing seeded patients only; the cohort includes at least one open admission, one active emergency visit, one closed visit, and one invoice per status; every newly seeded record records a `system`-actor CREATE audit event on first seed only (GAPFIX-026 convention); a rerun inserts zero rows and zero events; no new environment flags are introduced (`MEDICORE_DEMO_SEED` remains the single opt-in).

**Security/audit:** obviously synthetic names/identifiers; no secrets; provisioning flags untouched (operational support stays out of the backlog, §3).

**Acceptance:** `DemoDataInitializerTest` proves idempotency and system-actor events for the expanded cohort; a seeded local run shows non-zero dashboard aggregates; suites green.

**Run:** `cd backend && mvn -Dtest=DemoDataInitializerTest test && mvn test`

### Task 9 — Extend the journey smoke and re-baseline performance evidence

**Goal:** The repeatable smoke covers the full care-operations journey, and the regression tripwire is re-recorded honestly.

**Dependencies:** Tasks 2–8 (flows, dashboard keys, and fixtures must exist).

| Action | Path |
|---|---|
| Modify | `scripts/smoke-patient-journey.sh` |
| Modify | `docs/performance.md` |

**Key contract:** the script's journey extends to: admission create → discharge, emergency visit create → transitions, invoice create → status transition (the script's single admin login can perform all steps; RBAC evidence remains the test suites' job), plus assertions on the new dashboard keys. Failure contract unchanged: non-zero exit on any non-2xx, missing field, or unresolvable reference. `docs/performance.md` gains a new dated baseline with the same environment-assumptions discipline and an explicit note that the journey changed — old numbers are superseded, never compared silently.

**Security/audit:** the script keeps requiring `HOSPITAL_SMOKE_PASSWORD` from the environment; no credentials in the repository; synthetic data only; run against a disposable local backend prepared per `docs/runbook.md`.

**Acceptance:** a full smoke run passes end to end; the new baseline is dated and reproducible; no capacity or production claim.

**Run (prerequisite: backend started per `docs/runbook.md` with `MEDICORE_DEMO_SEED=true` and disposable `HOSPITAL_ADMIN_PASSWORD`/`HOSPITAL_JWT_SECRET` values):** `./scripts/smoke-patient-journey.sh` with `HOSPITAL_SMOKE_PASSWORD` set; then `cd backend && mvn test && cd ../frontend && npm test && npm run build`

### Task 10 — Documentation, portfolio evidence, and acceptance stop gate

**Goal:** Document exactly what Phase 2 demonstrates, then stop.

**Dependencies:** Tasks 1–9.

| Action | Path |
|---|---|
| Create | `docs/care-operations.md` |
| Create | `docs/architecture/care-operations.md` |
| Modify | `docs/api.md` |
| Modify | `docs/traceability.md` |
| Modify | `docs/implementation-status.md` |
| Modify | `docs/patient-journey.md` |
| Modify | `README.md` |
| Modify (accuracy pass only) | `docs/runbook.md` |

**Key contract:** `docs/api.md` documents the three normalized families, transition rules, the extended role matrix (including BILLING's isolation), and the extended dashboard contract; `docs/care-operations.md` tells the demonstrable story (appointment → admission → discharge; emergency visit; invoice) with the non-clinical/financial-simulation boundaries stated verbatim; `docs/architecture/care-operations.md` diagrams UI → feature API adapter → controller → service → repository/audit, mirroring the Phase 1 architecture document's style; `docs/traceability.md` links each Phase 2 requirement to its tests and smoke evidence; the accuracy pass verifies every endpoint, path, command, and claim against source.

**Security/audit:** no credentials, tokens, real personal data, private addresses, or machine-local paths in any tracked file.

**Acceptance:** documentation references only existing endpoints, files, commands, and observed behavior; `git diff --check` clean; all suites and the smoke pass from a clean checkout following only repository instructions.

**Run:** `cd backend && mvn test && cd ../frontend && npm test && npm run build && cd .. && ./scripts/smoke-patient-journey.sh` (prerequisite: prepared disposable local backend) `&& git diff --check`

## 6. PR map and execution order

| Order | Task | PR (one per task, no ceremonial PRs) |
|---|---|---|
| 1 | Characterization | `test: characterize care-operations contracts and authorization baseline` |
| 2 | Admissions | `feat: admissions workflow with verified patient references and discharge lifecycle` |
| 3 | Emergency | `feat: emergency-visit workflow with non-clinical triage label and status transitions` |
| 4 | Invoices | `feat: invoice lifecycle with unique invoice numbers (financial simulation)` |
| 5 | Dashboard | `feat: status-aware operations dashboard` |
| 6 | RBAC alignment | `test: align role-aware navigation with the enforced care-operations RBAC matrix` |
| 7 | Audit sweep | `test: pin audit coverage and failure contracts for care operations` |
| 8 | Fixtures | `feat: expand opt-in demo cohort with care-operations records` |
| 9 | Smoke/perf | `test: extend journey smoke with care-operations steps and re-baseline` |
| 10 | Docs/gate | `docs: care-operations portfolio evidence and acceptance review` |

Order is strict where dependencies say so (1 → 2 → 3 → 4 → 5; 6–10 after 5). Tasks 3 and 4 are independent of each other and could be reviewed in either order, but the sequence above is the default.

## 7. Risks and owner decisions (input needed later — not assumed)

1. **Write-role narrowing.** The current server family rule lets DOCTOR and NURSE write admissions/emergency records, and only ADMIN/BILLING touch invoices. This plan mirrors the enforced rules and invents nothing. If Mamdouh wants narrower transitions (e.g., doctor-only discharge), that is a `SecurityConfig` decision to make before Task 2, not after.
2. **Beds.** Admissions currently carry no bed reference and `/api/beds` stays raw CRUD. Linking beds into admissions is a natural Phase 3 candidate but expands schema and scope — deferred pending an owner decision.
3. **Triage semantics.** `1–5` is deliberately a meaningless demo label. Adopting any real triage scale would be a clinical-adjacent decision requiring explicit owner approval and is out of scope.
4. **Invoice realism boundary.** Multi-currency, tax, refunds, and the `/api/insurance-claims` family stay out. Any move toward real money movement is a Pilot-gate escalation.
5. **Legacy String columns.** New contracts follow the appointment precedent (typed in, canonical string stored) to avoid destructive migration. Real column migration belongs to the Pilot-gated PostgreSQL rehearsal.
6. **BILLING demo account.** The review-accounts bootstrap creates doctor/nurse accounts only. Demonstrating the invoice screen needs an ADMIN or BILLING login; adding an opt-in BILLING review account is an operational provisioning decision, not product backlog.
7. **Dashboard growth.** Flat count keys keep the contract simple; if role-specific or time-windowed views are wanted later, that is a new decision with its own contract work.

## 8. Stop gate

After Task 10, conduct a formal Training/Portfolio acceptance review comparing delivered behavior against §4. Do not start Pilot work, introduce real patient or hospital data, add payment/insurer/clinical integrations, adopt a real triage protocol, or claim production readiness without Mamdouh's explicit, separate approval. Phase 2 acceptance authorizes nothing beyond the Training/Portfolio boundary.
