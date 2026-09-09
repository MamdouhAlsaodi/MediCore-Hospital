# API Quick Start

MediCore is an educational, non-clinical training project — this API is documented for local training use only and must never carry real patient data.

## Base and auth

1. Start the backend on loopback port `5501` (`server.address: 127.0.0.1`, `server.port: 5501` in `backend/src/main/resources/application.yml`). Boot commands are in `docs/runbook.md`.
2. `POST /api/auth/login` with `{"username":"admin","password":"<value of HOSPITAL_ADMIN_PASSWORD supplied at backend startup>"}`.
3. Send the returned token on every call as `Authorization: Bearer <accessToken>`.

The admin password is never stored in this repository. Set `HOSPITAL_ADMIN_PASSWORD` in the runtime environment (at least 12 characters) before first start; the initial `admin` account is created only when it does not exist yet, and an existing account is never modified. `HOSPITAL_JWT_SECRET` is also required from the environment.

Login response (`AuthController.LoginResponse` — exactly these fields):

```json
{ "accessToken": "<JWT>", "tokenType": "Bearer", "username": "admin", "roles": ["ADMIN"] }
```

Rejected logins return `401` with a uniform, non-enumerating body: `{"error":"Invalid username or password."}`.

## Roles

Accounts carry roles from `Role` (`ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST`, `LAB_TECH`, `RADIOLOGY_TECH`, `PHARMACIST`, `BILLING`, `HR`, `STAFF`). Authorization is enforced server-side in `SecurityConfig`: public endpoints are only `/api/auth/**` and `/actuator/health`; explicit family rules apply, ordered before an ADMIN-only catch-all for unmatched `/api/**` (default deny); any authenticated user may reach `/api/dashboard/**`. Unauthenticated requests get `401`; an authenticated role without permission gets `403`.

### Enforced role matrix — demonstrated workflow

Method-level rules (ordered before the family rules) make the write policy server-enforced, not UI-only:

| Endpoint | ADMIN | DOCTOR | NURSE | RECEPTIONIST | Other (e.g. BILLING) |
|---|---|---|---|---|---|
| `GET /api/patients`, `GET /api/patients/{id}` | 2xx | 2xx | 2xx | 2xx | 403 |
| `POST /api/patients` | 2xx | 403 | 403 | 2xx | 403 |
| `PUT /api/patients/{id}` | 2xx | 403 | 403 | 2xx | 403 |
| `GET /api/appointments` | 2xx | 2xx | 2xx | 2xx | 403 |
| `POST /api/appointments` | 2xx | 403 | 403 | 2xx | 403 |
| `GET /api/staff` | 2xx | 403 | 403 | 2xx | 403 |
| `POST/DELETE /api/staff/*`, `/api/departments/**`, `/api/shifts/**` | 2xx | 403 | 403 | 403 (HR: 2xx) | 403 |
| `GET /api/audit` | 2xx | 403 | 403 | 403 | 403 |
| `GET /api/dashboard` | 2xx | 2xx | 2xx | 2xx | 2xx (any authenticated) |

Pinned by `SecurityAuthorizationTest` (the `…EnforcesAdminReceptionistOnlyWrites` and `staffDirectoryAllowsReceptionistReadWhileWritesStayAdminHrOnly` tests).

## Demonstrated workflow endpoints

### Patients — `/api/patients`

- `POST /api/patients` — create. Body (`CreatePatientRequest`): required `medicalRecordNumber`, `fullName`; optional `dateOfBirth` (`yyyy-MM-dd`), `sex`, `phone`, `email` (`@Email`-validated), `nationalId`, `address`. Blank `dateOfBirth` is accepted as `null`.
- `GET /api/patients?q=<query>` — list; `q` filters by `fullName` containing the query, case-insensitive; omitted/blank returns all.
- `GET /api/patients/{id}` — detail by UUID.
- `PUT /api/patients/{id}` — update. Body (`UpdatePatientRequest`) permits exactly `{fullName, phone, email, address}`; MRN, date of birth, sex, and national ID are immutable through this contract.

Every response is a `PatientResponse`:

```json
{ "id": "uuid", "medicalRecordNumber": "…", "fullName": "…", "dateOfBirth": "1980-01-01",
  "sex": "…", "phone": "…", "email": "…", "nationalId": "…", "address": "…", "active": true }
```

Known gap: duplicate `medicalRecordNumber` is enforced only by the database unique constraint, so a real duplicate may return `500` rather than `400`/`409` (see `docs/implementation-status.md`).

### Appointments — `/api/appointments`

- `POST /api/appointments` — create with `CreateAppointmentRequest`: `patientId` and `professionalId` (`@NotNull UUID`, must resolve to existing records or `404`), `scheduledAt` (ISO `LocalDateTime`), `type` (`@NotBlank`), `status` (`@Pattern`: `scheduled|confirmed|completed|cancelled`).
- `GET /api/appointments` — list; `GET /api/appointments/{id}` — detail; `DELETE /api/appointments/{id}` — delete. Delete carries no method-level rule, so it falls under the four-role family rule (`/api/appointments/**` → ADMIN, DOCTOR, NURSE, RECEPTIONIST); service-side reference and audit rules still apply.

Response (`AppointmentResponse`): `{id, patientId, professionalId, scheduledAt, type, status}` — references are the canonical UUID strings of verified records. Create and delete are atomic with their audit events (`AppointmentService`).

### Staff directory — `/api/staff`

- `GET /api/staff` — professional directory behind appointment scheduling (ADMIN/HR/RECEPTIONIST read). `StaffMemberResponse`: `{id, employeeCode, fullName, profession, licenseNumber, department}`. Writes (`POST`, `DELETE`) are ADMIN/HR.

### Audit — `/api/audit`

- `GET /api/audit` (ADMIN only) — all audit events; no ordering parameters (the frontend sorts newest-first client-side). Event: `{id, actor, action, resourceType, resourceId, details, occurredAt}` plus persistence metadata. Patient create/update and appointment create/delete produce events; failed operations do not. Demo-seeded rows bypass services and produce no events.

### Dashboard — `/api/dashboard`

- `GET /api/dashboard` (any authenticated role) — `{"patients":n,"appointments":n,"admissions":n,"emergencyVisits":n,"invoices":n}`.

### Other endpoint families

Additional training-surface families exist behind their own `SecurityConfig` role rules: `/api/clinical-encounters`, `/api/admissions`, `/api/beds`, `/api/emergency-visits`, `/api/nursing-observations`, `/api/lab-orders`, `/api/radiology-orders`, `/api/drugs`, `/api/medication-orders`, `/api/surgeries`, `/api/invoices`, `/api/insurance-claims`, `/api/inventory-items`, `/api/blood-units`, `/api/diet-orders`, `/api/facility-work-orders`, `/api/documents`, `/api/notifications`, `/api/departments`, `/api/shifts`. Only the workflow above is exercised by the demonstrated journey, the smoke script, and the frontend screens; the rest is repository CRUD with the shared error contract.

## Error contract

Client errors are mapped in one place, `shared/GlobalExceptionHandler`, always producing the stable `ApiError` JSON shape:

```json
{ "timestamp": "2026-09-09T12:00:00Z", "status": 400, "error": "Validation Error",
  "message": "fullName: must not be blank", "path": "/api/patients" }
```

| Situation | Status | Body |
|---|---|---|
| No/expired session | `401` | `{"error":"authentication required"}` (security entry point) |
| Role refused | `403` | `{"error":"access denied"}` (access-denied handler) |
| Rejected login | `401` | `{"error":"Invalid username or password."}` |
| Validation failure (`@Valid`) | `400` | `ApiError`, `error: "Validation Error"`, message is a comma-joined field summary |
| Malformed path UUID | `400` | `ApiError`, "Invalid path value: <name>" |
| Malformed request body (bad JSON, non-UUID reference, unparseable date/time) | `400` | `ApiError`, "Malformed request body" |
| Unknown record | `404` | `ApiError`, e.g. "Patient not found: <uuid>" |

## Synthetic demo data (opt-in)

`MEDICORE_DEMO_SEED=true` at backend startup seeds a small synthetic cohort (3 patients `DEMO-0001..0003`, 2 professionals `DEMO-STAFF-001..002`, 2 appointments). Default is **off**; seeding is idempotent, never deletes or modifies existing rows, and creates no accounts. See `docs/runbook.md` for the exact commands and caveats.
