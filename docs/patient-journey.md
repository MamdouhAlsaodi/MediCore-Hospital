# The Demonstrated Patient Journey

MediCore is an **educational, non-clinical training project**. It is not certified medical software, holds no regulatory approval, and must never be used for patient care or real clinical decisions. Everything described below is synthetic-data-only behavior of the local training build.

This document describes the one workflow the Training/Portfolio milestone actually demonstrates — login → patient registration → search/detail → edit → appointment scheduling → audit evidence — exactly as implemented in code and pinned by automated tests. Every endpoint, field, and behavior claim was checked against source (see `docs/traceability.md` for the test and evidence map).

## Before you start

1. Start the backend on loopback port `5501` with the synthetic demo cohort enabled (professional references must resolve for scheduling) — exact commands are in `docs/runbook.md`. Credentials come only from runtime environment variables; nothing is stored in the repository.
2. Start the frontend dev server or preview server (port `5502`, proxies `/api` to the backend) — also in `docs/runbook.md`.
3. Log in with the `admin` account you provisioned via `HOSPITAL_ADMIN_PASSWORD` (the initial `admin` account is created only when it does not exist yet). `ADMIN` can perform every step of the journey; a `RECEPTIONIST` account can perform everything except the audit screen.

No browser screenshots are included at this stage: the plan allows screenshots only after a browser/mobile verification pass, which has not been run. The workflow is verified by automated tests and the `scripts/smoke-patient-journey.sh` API smoke instead.

## Step 1 — Login

- **Who:** any role with an account (`ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST` and the other configured roles).
- **UI:** the dedicated Login page (`LoginPage.jsx`) posts through `loginRequest` in `frontend/src/api.js`.
- **API:** `POST /api/auth/login` with `{"username":"admin","password":"<runtime value>"}`.

Success response (`AuthController.LoginResponse` — exactly these fields):

```json
{ "accessToken": "<JWT>", "tokenType": "Bearer", "username": "admin", "roles": ["ADMIN"] }
```

The frontend stores the session in `sessionStorage` only (cleared when the tab closes) and sends `Authorization: Bearer <accessToken>` on every API call through the shared `apiFetch`.

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| Wrong username/password | `401` `{"error":"Invalid username or password."}` | Inline "Invalid username or password." (uniform message; does not reveal which part was wrong) |
| Missing/blank fields | `400` `ApiError` validation body | Inline error notice |
| Backend unreachable | fetch throws | `ApiError(0, "Network error: the server is unreachable.")` shown inline |

## Step 2 — Dashboard

- **Who:** any authenticated role (`/api/dashboard/**` is `authenticated()`; navigation shows Dashboard to everyone logged in).
- **UI:** default screen after login (`DashboardPage.jsx`), fetched via `apiFetch`.
- **API:** `GET /api/dashboard` → `{"patients":n,"appointments":n,"admissions":n,"emergencyVisits":n,"invoices":n}` (five count keys, `DashboardController`).
- **Failure:** `401` → the shell clears the session and returns to Login; no other state is offered for this endpoint.

## Step 3 — Patient search and detail

- **Who:** `ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST` (read); other roles get `403` from the server and no Patients destination in the UI.
- **UI:** Patients screen (`PatientsPage.jsx`) — a semantic search form over the patient list.
- **API:** `GET /api/patients?q=<url-encoded trimmed query>` (or `GET /api/patients` for the unfiltered list) → array of `PatientResponse`:

```json
{
  "id": "uuid", "medicalRecordNumber": "DEMO-0001", "fullName": "Demo Patient Alpha",
  "dateOfBirth": "1980-01-01", "sex": "unspecified", "phone": "+10000000000",
  "email": "alpha@synthetic.example.test", "nationalId": "NID-0001",
  "address": "1 Synthetic Street", "active": true
}
```

Search matches `fullName` containing the query, case-insensitive (`PatientRepository.findByFullNameContainingIgnoreCase`). Selecting a row (native button, keyboard-operable) opens the read-only detail view (`PatientDetailPage.jsx`) showing MRN, name, date of birth, sex, phone, email, address, and Active/Inactive — **National ID is deliberately never rendered** (data minimization; pinned by test).

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| No session / expired | `401` | Session-expiry callback returns the shell to Login; no local error is stacked on top |
| Role without read permission (e.g. `BILLING`) | `403` | Shared permission-denial alert ("You do not have permission to view this data.") |
| Empty result set | `200` `[]` | `role="status"` guidance echoing the query and suggesting another search |
| Malformed UUID path value | `400` `ApiError` "Invalid path value: id" | Error notice with the `ApiError` message |

## Step 4 — Register a patient

- **Who:** `ADMIN`, `RECEPTIONIST` only — enforced server-side (`SecurityConfig`: `POST /api/patients` → `hasAnyRole("ADMIN","RECEPTIONIST")`) and mirrored in the UI permission map (`DOCTOR` gets a read-only form view instead; `NURSE` gets no form action).
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

Required: `medicalRecordNumber`, `fullName` (both `@NotBlank`). `email` is validated with `@Email` when present; a blank `dateOfBirth` is sent as `null` for Jackson `LocalDate` compatibility. Success returns the created `PatientResponse`; the UI shows a visible "Patient registered successfully." confirmation (`role="status"`) and returns to the detail view, and the list refetches.

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| Missing required fields / bad email format | `400` `ApiError` "Validation Error" with a field summary | Inline `role="alert"` notice, zero field loss |
| Duplicate medical record number | **Known gap:** enforced only by the DB unique constraint, so it may surface as `500` rather than a clean `400`/`409` (see `docs/implementation-status.md`) | Inline error notice with the generic failure message |
| Role without write permission (`DOCTOR`/`NURSE`) | `403` | Shared permission-denial message |

## Step 5 — Edit the patient

- **Who:** `ADMIN`, `RECEPTIONIST` (`PUT /api/patients/{id}` is role-restricted the same way as create). `DOCTOR` can open the same form read-only ("View record form"); `NURSE` gets no form action.
- **API:** `PUT /api/patients/{id}` with `UpdatePatientRequest` — **only** these four fields are editable:

```json
{ "fullName": "Updated Name", "phone": "+10000000001", "email": "updated@synthetic.example.test", "address": "2 Synthetic Street" }
```

The medical record number, date of birth, sex, and national ID are immutable through this contract; the frontend adapter (`updatePatient` in `patientApi.js`) is built so it cannot send them, and MRN is disabled in the edit form. Success returns the updated `PatientResponse`; the UI shows "Changes saved." and patches the loaded list in place.

Failure states are the same pattern as create (`400` validation with inline notice and zero field loss, `403` permission denial, `401` session expiry); an unknown id yields `404` "Patient not found: <uuid>".

## Step 6 — Schedule an appointment

- **Who:** `ADMIN`, `RECEPTIONIST` only (`SecurityConfig`: `POST /api/appointments` → `hasAnyRole("ADMIN","RECEPTIONIST")`; the UI hides the action from `DOCTOR`/`NURSE`, and those roles' direct API calls receive `403`).
- **UI:** "Schedule appointment" on the Appointments screen or preselected on the patient detail view (`AppointmentForm.jsx`). Patient and professional are chosen from loaded domain records — no typed identifiers. Professionals load from the staff directory (`GET /api/staff`, readable by `ADMIN`, `HR`, `RECEPTIONIST`); if that directory is refused, the form disables itself with a clear reason.
- **API:** `POST /api/appointments` with `CreateAppointmentRequest`:

```json
{
  "patientId": "<patient uuid>",
  "professionalId": "<staff uuid>",
  "scheduledAt": "2031-01-15T10:00:00",
  "type": "consultation",
  "status": "scheduled"
}
```

`patientId`/`professionalId` must be real references (`AppointmentService` resolves both before persisting), `scheduledAt` is a typed ISO `LocalDateTime`, and `status` must be one of the lowercase contract values `scheduled|confirmed|completed|cancelled`. Success returns the `AppointmentResponse`:

```json
{ "id": "uuid", "patientId": "<canonical uuid string>", "professionalId": "<canonical uuid string>",
  "scheduledAt": "2031-01-15T10:00:00", "type": "consultation", "status": "scheduled" }
```

The list refetches after a successful create (never a local patch); the detail view shows a visible "Appointment scheduled." confirmation.

Failure states:

| Case | Response | UI behavior |
|---|---|---|
| Patient reference does not exist | `404` "Patient not found: <uuid>" | Inline `role="alert"` notice, form state preserved |
| Professional reference does not exist | `404` "Professional not found: <uuid>" | Inline notice |
| Malformed (non-UUID) references or unparseable `scheduledAt` | `400` "Malformed request body" | Inline notice |
| Invalid status value or blank fields | `400` validation summary | Inline notice, zero field loss |
| Role without scheduling permission | `403` | Shared permission-denial message |

## Step 7 — Audit evidence (ADMIN only)

- **Who:** `ADMIN` only — `SecurityConfig` maps `/api/audit/**` to `hasRole("ADMIN")`; non-admin roles receive `403` (pinned by `SecurityAuthorizationTest`) and the Audit destination is hidden in the navigation via the permission map.
- **UI:** Audit screen (`AuditPage.jsx`) — a read-only, newest-first evidence table showing Time (`occurredAt`), Actor, Action, Entity type (`resourceType`), and Identifier (`resourceId`). The `details` payload and persistence metadata are deliberately never rendered, and no token, credential, or request body ever appears.
- **API:** `GET /api/audit` → array of audit events:

```json
{
  "id": "uuid", "actor": "admin", "action": "CREATE", "resourceType": "Patient",
  "resourceId": "<patient uuid>", "details": "<MRN for patient creates>",
  "occurredAt": "2026-09-09T12:00:00Z",
  "createdAt": "…", "updatedAt": "…", "version": 0
}
```

Successful patient create/update and appointment create/delete produce events (`PatientService`/`AppointmentService` record them atomically with the mutation); failed validations create neither records nor events (pinned by `failedAppointmentValidationCreatesNeitherAppointmentNorAudit`). Note that rows written directly by the opt-in demo seeder bypass the services and therefore produce **no** audit events (documented in `docs/runbook.md`).

## Failure-state summary

`401` always means "no valid session": the frontend invokes the session-expiry callback and the shell returns to Login — no local error is raised on top of it. `403` always means "authenticated but this role is refused": the backend is authoritative, and the UI shows the shared permission-denial message. `400` covers validation and malformed requests with the stable `ApiError` body; `404` covers missing records. All error bodies and UI behaviors above are pinned by tests — see `docs/traceability.md`.

## How to verify this journey yourself

```bash
# automated suites
cd backend && mvn test                       # 32 tests
cd ../frontend && npm test && npm run build  # 61 tests + production build

# repeatable API smoke of the same journey (backend must be running;
# disposable local database + demo seed — see docs/runbook.md)
BASE_URL=http://127.0.0.1:5501 RUNS=3 \
HOSPITAL_SMOKE_PASSWORD="<disposable local value>" \
./scripts/smoke-patient-journey.sh
```

The smoke script performs login → synthetic patient create → search → detail → appointment create → appointment list → dashboard per run, fails non-zero on any contract deviation, and prints per-step timings (baseline recorded in `docs/performance.md`).
