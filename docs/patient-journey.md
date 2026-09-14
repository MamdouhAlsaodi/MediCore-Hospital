# The Demonstrated Patient Journey

MediCore is an **educational, non-clinical training project**. It is not certified medical software, holds no regulatory approval, and must never be used for patient care or real clinical decisions. Everything described below is synthetic-data-only behavior of the local training build.

This document describes the demonstrated patient journey — login and acting-context selection → patient registration → search/detail → edit → availability-aware appointment scheduling → audit evidence — exactly as implemented in code and pinned by automated tests. Since Phase 3 every step happens **inside one branch of one synthetic organization**: the server binds each session to an acting context and scopes every read and write to that branch. The journey continues after the appointment into **care operations** (admission with bed assignment/transfer and server-stamped discharge, an emergency visit with a neutral demo triage label, a uniquely numbered simulated invoice, and the branch/network command centers): that continuation is told in [`docs/care-operations.md`](care-operations.md), with the Phase 3 layering in [`docs/architecture/multi-branch-operations.md`](architecture/multi-branch-operations.md). Every endpoint, field, and behavior claim was checked against source (see `docs/traceability.md` for the test and evidence map).

## Before you start

1. Start the backend on loopback port `5501` with the synthetic three-branch demo cohort enabled (`MEDICORE_DEMO_SEED=true` — patient, professional, and bed references must resolve) — exact commands are in `docs/runbook.md`. Credentials come only from runtime environment variables; nothing is stored in the repository.
2. Start the frontend dev server or preview server (port `5502`, proxies `/api` to the backend) — also in `docs/runbook.md`. For real-browser evidence, the dedicated loopback review pair (backend `5591`, frontend `5592`) is driven by `npm run test:e2e`; its committed screenshots live in `docs/evidence/phase3/`.
3. Log in with the `admin` account you provisioned via `HOSPITAL_ADMIN_PASSWORD` (the initial `admin` account, with its `ORGANIZATION`-scope `ADMIN` assignment, is created only when it does not exist yet). `ADMIN` can perform every step of the journey; a `RECEPTIONIST` account can perform everything except the audit screen — each within the branch of its acting context.

The real-browser journeys that pin this story at desktop and mobile viewports are part of the automated gate (`npm run test:e2e`), and the API-level smoke exercises the same journey end to end (`scripts/smoke-patient-journey.sh`).

## Step 1 — Login and acting context

- **Who:** any account with an enabled acting assignment (`ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST` and the other configured roles — authority comes from the assignment, not from a global role set).
- **UI:** the dedicated Login page (`LoginPage.jsx`) posts through `authenticate` in `frontend/src/api.js`; the shell then displays username, acting role, and the server-named branch, with the acting-context selector (`BranchSelector.jsx`) offering exactly the server-issued (assignment, branch) pairs.
- **API:** `POST /api/auth/login` with `{"username":"admin","password":"<runtime value>"}`.

Success response (`ActingContextService.Session` — exactly these fields):

```json
{ "accessToken": "<JWT>", "tokenType": "Bearer", "username": "admin", "roles": ["ADMIN"],
  "assignments": [ { "id": "<assignment uuid>", "role": "ADMIN", "scope": "ORGANIZATION",
                     "organizationId": "<uuid>", "organizationLabel": "Demo Synthetic Hospital",
                     "branchId": null, "branchLabel": null,
                     "departmentId": null, "departmentLabel": null, "enabled": true } ],
  "actingContext": { "username": "admin", "assignmentId": "<assignment uuid>", "role": "ADMIN",
                     "scope": "ORGANIZATION", "organizationId": "<uuid>",
                     "branchId": "<selected branch uuid>", "departmentId": null } }
```

The frontend stores the session in `sessionStorage` only (cleared when the tab closes) and sends the authenticated request header on every API call through the shared `apiFetch`. A stored session is accepted on reload only when the complete server-issued structure is present and self-consistent; otherwise it is discarded.

**Switching branch or role** calls `POST /api/auth/context` with `{assignmentId, branchId?}`; the server issues a **replacement context-bound token** and the app atomically replaces the stored session and remounts the current screen — no stale-branch content survives. The selector lists only server-returned assignments/branches; a branch id alone in storage is never authority.

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| Wrong username/password, disabled account, or no valid assignment/branch | `401` `{"error":"Invalid username or password."}` | Inline "Invalid username or password." (uniform, non-enumerating) |
| Context switch to a foreign/unknown/disabled assignment or inactive branch | `403` `ApiError` "The selected assignment or branch is not available." | Login preserved; shared denial notice |
| Missing/blank login fields | `400` `ApiError` validation body | Inline error notice |
| Backend unreachable | fetch throws | `ApiError(0, "Network error: the server is unreachable.")` shown inline |

## Step 2 — Branch command center (dashboard)

- **Who:** any authenticated role for the branch summary (`/api/dashboard/**` is `authenticated()`); the network comparison is served only to `ORGANIZATION`-scope ADMIN contexts, enforced in `DashboardService`.
- **UI:** default screen after login (`DashboardPage.jsx`): the acting branch line (`CODE — Name`), the branch summary cards, and — for organization ADMIN — the network comparison listing every active branch with drill-downs into branch-scoped screens.
- **API:** `GET /api/dashboard/branch` → the typed `BranchSummary` (branch identity, the five workflow totals, open admissions, active emergency visits, bed counts by status, today's appointments, invoice buckets); `GET /api/dashboard/network` → organization totals (exactly the sum of the per-branch summaries) plus every active branch. `GET /api/dashboard` remains a deprecated alias of the branch summary. Full contract in `docs/api.md`.
- **Failure:** `401` → the shell clears the session and returns to Login; a non-organization context calling `/api/dashboard/network` is refused.

## Step 3 — Patient search and detail

- **Who:** `ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST` (read); other roles get `403` from the server and no Patients destination in the UI. Results are the acting branch's patients only — a neighboring branch's MRN simply does not exist here (the browser journey proves `DEMO-0004` is a search miss from the main branch).
- **UI:** Patients screen (`PatientsPage.jsx`) — a semantic search form over the patient list.
- **API:** `GET /api/patients?q=<url-encoded trimmed query>` (or `GET /api/patients` for the unfiltered branch list) → array of `PatientResponse`:

```json
{
  "id": "uuid", "branchId": "<acting branch uuid>", "medicalRecordNumber": "DEMO-0001",
  "fullName": "Demo Patient Alpha", "dateOfBirth": "1980-01-01", "sex": "unspecified",
  "phone": "+10000000000", "email": "alpha@synthetic.example.test", "nationalId": "NID-0001",
  "address": "1 Synthetic Street", "active": true
}
```

Search matches `fullName` containing the query, case-insensitive, inside the acting branch (`PatientRepository.findByBranchIdAndFullNameContainingIgnoreCase`). Selecting a row (native button, keyboard-operable) opens the read-only detail view (`PatientDetailPage.jsx`) showing MRN, name, date of birth, sex, phone, email, address, and Active/Inactive — **National ID is deliberately never rendered** (data minimization; pinned by test).

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| No session / expired, or context no longer valid server-side | `401` | Session-expiry callback returns the shell to Login; no local error is stacked on top |
| Role without read permission (e.g. `BILLING`) | `403` | Shared permission-denial alert ("You do not have permission to view this data.") |
| Empty result set (including a cross-branch MRN) | `200` `[]` | `role="status"` guidance echoing the query and suggesting another search |
| Malformed UUID path value | `400` `ApiError` "Invalid path value: id" | Error notice with the `ApiError` message |
| Cross-branch or unknown detail id | `404` generic "Patient not found: <uuid>" | Error notice — cross-branch access is indistinguishable from a missing record |

## Step 4 — Register a patient

- **Who:** `ADMIN`, `RECEPTIONIST` only — enforced server-side (`SecurityConfig`: `POST /api/patients` → `hasAnyRole("ADMIN","RECEPTIONIST")`) and mirrored in the UI permission map (`DOCTOR` gets a read-only form view instead; `NURSE` gets no form action). The new record is owned by the acting branch — the request carries no branch field, and no client input can choose ownership.
- **UI:** "New patient" action on the Patients screen (`RECEPTIONIST`/`ADMIN` only) opens `PatientForm.jsx`.
- **API:** `POST /api/patients` with `CreatePatientRequest`:

```json
{
  "medicalRecordNumber": "SMOKE-1736000000-1",
  "fullName": "Smoke Patient Run 1",
  "dateOfBirth": "2000-01-01", "sex": "unspecified",
  "phone": "+10000000000", "email": "smoke-1@synthetic.example.test",
  "nationalId": "NID-SMOKE-1736000000-1", "address": "1 Synthetic Street"
}
```

Required: `medicalRecordNumber`, `fullName` (both `@NotBlank`). `email` is validated with `@Email` when present; a blank `dateOfBirth` is sent as `null` for Jackson `LocalDate` compatibility. Success returns the created `PatientResponse`; the UI shows a visible "Patient registered successfully." confirmation (`role="status"`) and returns to the detail view, and the list refetches. MRN uniqueness is global by explicit contract; branch scope is not a uniqueness boundary.

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| Missing required fields / bad email format | `400` `ApiError` "Validation Error" with a field summary | Inline `role="alert"` notice, zero field loss |
| Duplicate medical record number | `409` `ApiError` `{"status":409,"error":"Conflict","message":"Medical record number already exists","path":"/api/patients"}` — service pre-check; a race-lost unique-constraint violation is also mapped to `409` with a fixed generic conflict message | Inline `role="alert"` notice with the shared generic failure message ("The request failed (409). Please try again."), zero field loss, no success callback; the original record is left unchanged |
| Role without write permission (`DOCTOR`/`NURSE`) | `403` | Shared permission-denial message |

## Step 5 — Edit the patient

- **Who:** `ADMIN`, `RECEPTIONIST` (`PUT /api/patients/{id}` is role-restricted the same way as create). `DOCTOR` can open the same form read-only ("View record form"); `NURSE` gets no form action. Only same-branch records resolve — a foreign id is `404`.
- **API:** `PUT /api/patients/{id}` with `UpdatePatientRequest` — **only** these four fields are editable:

```json
{ "fullName": "Updated Name", "phone": "+10000000001", "email": "updated@synthetic.example.test", "address": "2 Synthetic Street" }
```

The medical record number, date of birth, sex, and national ID are immutable through this contract; the frontend adapter (`updatePatient` in `patientApi.js`) is built so it cannot send them, and MRN is disabled in the edit form. Success returns the updated `PatientResponse`; the UI shows "Changes saved." and patches the loaded list in place.

Failure states are the same pattern as create (`400` validation with inline notice and zero field loss, `403` permission denial, `401` session expiry); an unknown or cross-branch id yields the generic `404` "Patient not found: <uuid>".

## Step 6 — Schedule an availability-aware appointment

- **Who:** `ADMIN`, `RECEPTIONIST` only (`SecurityConfig`: `POST /api/appointments` → `hasAnyRole("ADMIN","RECEPTIONIST")`; the UI hides the action from `DOCTOR`/`NURSE`, and those roles' direct API calls receive `403`).
- **UI:** "Schedule appointment" on the Appointments screen or preselected on the patient detail view (`AppointmentForm.jsx`). Patient and professional are chosen from loaded domain records — no typed identifiers. Professionals load from the branch-scoped staff directory (`GET /api/staff`, readable by `ADMIN`, `HR`, `RECEPTIONIST`); if that directory is refused, the form disables itself with a clear reason. Choosing a professional loads their modeled availability (`GET /api/staff/{id}/availability?from=&to=`) and the form displays it before you submit.
- **API:** `POST /api/appointments` with `CreateAppointmentRequest`:

```json
{
  "patientId": "<patient uuid>",
  "professionalId": "<staff uuid>",
  "scheduledAt": "2031-01-15T10:00:00",
  "durationMinutes": 30,
  "type": "consultation",
  "status": "scheduled"
}
```

`patientId`/`professionalId` must be same-branch references (`AppointmentService` resolves both inside the acting branch or answers the generic `404`), `durationMinutes` is required (5–480 — a bounded engineering validation range, never clinical policy), and the server computes `endsAt`. The conflict contract is enforced transactionally: the window must sit inside one containing same-branch availability interval of that professional, and must not overlap a non-cancelled appointment for them — either violation is `409` with a controlled message, nothing persisted, and no success audit event. Adjacent windows (one ends exactly when the next starts) are legal. Success returns the `AppointmentResponse`:

```json
{ "id": "uuid", "branchId": "<acting branch uuid>", "patientId": "<canonical uuid string>",
  "professionalId": "<canonical uuid string>", "scheduledAt": "2031-01-15T10:00:00",
  "durationMinutes": 30, "endsAt": "2031-01-15T10:30:00", "type": "consultation", "status": "scheduled" }
```

The list refetches after a successful create (never a local patch); the detail view shows a visible "Appointment scheduled." confirmation. The browser journey pins the failure UX: an overlapping and an outside-availability submission each keep every form field with the inline `409` alert (zero field loss), and closing the form leaves the table unchanged.

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| Patient/professional reference does not exist or belongs to another branch | `404` "Patient not found: <uuid>" / "Professional not found: <uuid>" | Inline `role="alert"` notice, form state preserved |
| Window outside modeled availability, or overlapping a booked appointment | `409` controlled conflict message | Inline `role="alert"` notice, zero field loss, no partial write |
| Overlapping availability interval (on the availability endpoint) | `409` | Inline notice; nothing persisted |
| Malformed (non-UUID) references or unparseable `scheduledAt` | `400` "Malformed request body" | Inline notice |
| Invalid status value, blank fields, or `durationMinutes` outside 5–480 | `400` validation summary | Inline notice, zero field loss |
| Role without scheduling permission | `403` | Shared permission-denial message |

## Step 7 — Audit evidence (ADMIN only)

- **Who:** `ADMIN` only — `SecurityConfig` maps `/api/audit/**` to `hasRole("ADMIN")`; non-admin roles receive `403` (pinned by `SecurityAuthorizationTest`) and the Audit destination is hidden in the navigation via the permission map. Within ADMIN, the acting context decides the visible slice server-side: an `ORGANIZATION`-scope ADMIN sees the whole organization (plus context-less `legacy/unassigned` rows), a branch-scoped ADMIN only its branch. Optional filters `branchId`, `resourceType`, `actor`, `correlationId` intersect the slice and can never widen it.
- **UI:** Audit screen (`AuditPage.jsx`) — a read-only, newest-first evidence table showing Time (`occurredAt`), Actor, Action, Entity type (`resourceType`), and Identifier (`resourceId`). The `details` payload, correlation id, and persistence metadata are deliberately never rendered, and no token, credential, or request body ever appears.
- **API:** `GET /api/audit` → array of scope-filtered audit event views:

```json
{ "id": "uuid", "actor": "admin", "action": "CREATE", "resourceType": "Patient",
  "resourceId": "<patient uuid>", "details": "<safe concise label>",
  "occurredAt": "2026-09-13T12:00:00Z", "assignmentId": "<uuid>", "role": "ADMIN",
  "scope": "ORGANIZATION", "organizationId": "<uuid>", "branchId": "<uuid>",
  "departmentId": null, "correlationId": "<bounded id>", "branchAttribution": null }
```

Every successful mutation on this journey writes exactly one event with the full acting context (assignment, role, scope, organization, branch) and the bounded correlation id; failed operations write none (pinned by dedicated tests). With the demo seed enabled, the fixture rows themselves are recorded on first seed with the `system` actor and their owning branch as the only context value — 54 events for the full first-run cohort, none on re-seed (composition in `docs/runbook.md`).

## Where the journey continues — care operations

The appointment is not the end of the demonstrated workflow. With the same synthetic patient, in the same acting branch, the care-operations phase demonstrates (all on `/api/admissions`, `/api/emergency-visits`, and `/api/invoices`, each behind its enforced role family and branch scope):

- **Admission with bed lifecycle** — a register form on the Admissions screen (also offered as "Register admission" on the patient detail view) creates an admission for a same-branch patient, optionally into an available bed; the server sets `ADMITTED`, assigns and transfers beds atomically, and only the server stamps `dischargedAt` while releasing the bed in the same transaction.
- **Emergency visit** — a register form with a **neutral `1–5` demo triage label** (no clinical meaning, not a real triage protocol) and guarded `WAITING → IN_TREATMENT → CLOSED` actions.
- **Invoice (financial simulation)** — ADMIN and BILLING only: a uniquely numbered, display-only demo invoice moved through `DRAFT → ISSUED → PAID` or voided; the screen states on-page that no real payments, conversion, FX, or tax exist.
- **Command centers and audit** — the branch summary, the organization network comparison (organization-ADMIN only), and the scope-aware audit screen surface every one of these mutations (exactly one context-attributed event per success, none on failure).

Step-by-step story with failure examples: [`docs/care-operations.md`](care-operations.md). Phase 3 layering and boundaries: [`docs/architecture/multi-branch-operations.md`](architecture/multi-branch-operations.md).

## Failure-state summary

`401` always means "no valid session/acting context": the frontend invokes the session-expiry callback and the shell returns to Login — no local error is raised on top of it. `403` always means "authenticated but this role/scope is refused": the backend is authoritative, and the UI shows the shared permission-denial message. `400` covers validation and malformed requests with the stable `ApiError` body; `404` covers missing and cross-branch records indistinguishably; `409` covers lifecycle, duplicate-key, and scheduling conflicts. All error bodies and UI behaviors above are pinned by tests — see `docs/traceability.md`.

## How to verify this journey yourself

```bash
# automated suites
cd backend && mvn test                        # 162 tests
cd ../frontend && npm test && npm run build   # 237 tests across 15 files + production build
cd ../frontend && npm run test:e2e            # real-browser journeys at two viewports

# repeatable API smoke of the branch-aware journey (a disposable local backend
# must already be running with the demo seed enabled — see docs/runbook.md)
BASE_URL=http://127.0.0.1:5501 RUNS=1 \
HOSPITAL_SMOKE_PASSWORD="<disposable local value>" \
./scripts/smoke-patient-journey.sh
```

Per run the smoke performs login → acting-context selection → synthetic patient create → search → detail → staff availability read → availability-aware appointment create → a required `409` overlap → two beds → admit with bed → atomic transfer → discharge with release → emergency visit through its terminal state → invoice through `PAID` → branch dashboard → organization network dashboard → filtered audit with acting context → a second-branch isolation proof, failing non-zero on any contract deviation, scope leakage, or partial bed mutation, and printing per-step timings (dated baseline in `docs/performance.md`). The care-operations half of that journey is documented in [`docs/care-operations.md`](care-operations.md).
