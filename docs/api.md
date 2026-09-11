# API Quick Start

MediCore is an educational, non-clinical training project — this API is documented for local training use only and must never carry real patient data. The invoice family below is a **financial simulation** (demo amounts and currency labels; no payments, tax, FX, or collection), and the emergency-visit triage label is a **neutral `1–5` demo value** with no clinical meaning.

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

### Enforced role matrix — demonstrated workflows

Method-level rules (ordered before the family rules) make the write policy server-enforced, not UI-only:

| Endpoint | ADMIN | DOCTOR | NURSE | RECEPTIONIST | BILLING |
|---|---|---|---|---|---|
| `GET /api/patients`, `GET /api/patients/{id}` | 2xx | 2xx | 2xx | 2xx | 403 |
| `POST /api/patients` | 2xx | 403 | 403 | 2xx | 403 |
| `PUT /api/patients/{id}` | 2xx | 403 | 403 | 2xx | 403 |
| `GET /api/appointments` | 2xx | 2xx | 2xx | 2xx | 403 |
| `POST /api/appointments` | 2xx | 403 | 403 | 2xx | 403 |
| `/api/admissions/**` (all methods, incl. `POST`/`PUT …/status`/`DELETE`) | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/emergency-visits/**` (all methods) | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/invoices/**` (all methods, incl. `POST`/`PUT …/status`/`DELETE`) | 2xx | 403 | 403 | 403 | 2xx |
| `GET /api/staff` | 2xx | 403 | 403 | 2xx | 403 |
| `POST/DELETE /api/staff/*`, `/api/departments/**`, `/api/shifts/**` | 2xx | 403 | 403 | 403 (HR: 2xx) | 403 |
| `GET /api/audit` | 2xx | 403 | 403 | 403 | 403 |
| `GET /api/dashboard` | 2xx | 2xx | 2xx | 2xx | 2xx (any authenticated) |

Pinned by `SecurityAuthorizationTest` (the `…EnforcesAdminReceptionistOnlyWrites`, `careOperationReadsAdmitExactlyTheDocumentedRoleFamilies`, `admissionWritesAdmitTheFourRolesWhileBillingPersistsNothing`, `emergencyWritesAdmitTheFourRolesWhileBillingPersistsNothing`, and `invoiceWritesAdmitAdminAndBillingWhileClinicalRolesPersistNothing` tests). The admission/emergency/invoice family rules admit or refuse every remaining configured role identically (e.g. `LAB_TECH` and `HR` get `403` on all three families); the matrix above shows the roles the demonstrated workflows use.

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

Duplicate `medicalRecordNumber` on create is rejected with `409 Conflict` (closed in PR #9, `d3526ab`): a pre-check in `PatientService.create` throws before any save, and a race-lost database unique-constraint violation is also mapped to `409` — see the error contract below.

### Appointments — `/api/appointments`

- `POST /api/appointments` — create with `CreateAppointmentRequest`: `patientId` and `professionalId` (`@NotNull UUID`, must resolve to existing records or `404`), `scheduledAt` (ISO `LocalDateTime`), `type` (`@NotBlank`), `status` (`@Pattern`: `scheduled|confirmed|completed|cancelled`).
- `GET /api/appointments` — list; `GET /api/appointments/{id}` — detail; `DELETE /api/appointments/{id}` — delete. Delete carries no method-level rule, so it falls under the four-role family rule (`/api/appointments/**` → ADMIN, DOCTOR, NURSE, RECEPTIONIST); service-side reference and audit rules still apply.

Response (`AppointmentResponse`): `{id, patientId, professionalId, scheduledAt, type, status}` — references are the canonical UUID strings of verified records. Create and delete are atomic with their audit events (`AppointmentService`).

### Admissions — `/api/admissions`

- `POST /api/admissions` — create with `CreateAdmissionRequest`: `patientId` (`@NotNull UUID`, must resolve or `404`), `admittedAt` (ISO `LocalDateTime`, stored as its canonical string), `reason` (`@NotBlank`, trimmed). The request deliberately carries no status field — the server sets `status=ADMITTED`.
- `GET /api/admissions`, `GET /api/admissions/{id}` — list/detail.
- `PUT /api/admissions/{id}/status` — body `{"status":"DISCHARGED"}`: the only defined transition, legal only from `ADMITTED`. The server stamps `dischargedAt`; the client cannot supply it.
- `DELETE /api/admissions/{id}` — service-owned and audited.

Response (`AdmissionResponse`) — exactly these fields, no persistence metadata:

```json
{ "id": "uuid", "patientId": "<canonical patient uuid string>", "admittedAt": "2026-01-15T10:00:00",
  "dischargedAt": null, "reason": "demo synthetic admission", "status": "ADMITTED" }
```

Status set: `ADMITTED | DISCHARGED`. Repeating a discharge or requesting any other target returns `409` with a controlled message.

### Emergency visits — `/api/emergency-visits`

- `POST /api/emergency-visits` — create with `CreateEmergencyVisitRequest`: `patientId` (`@NotNull UUID`, must resolve or `404`), `arrivalAt` (ISO `LocalDateTime`, canonical string), `triageLevel` (`@Pattern` `1|2|3|4|5`), `chiefComplaint` (`@NotBlank`, trimmed). The server sets `status=WAITING`.
- `GET /api/emergency-visits`, `GET /api/emergency-visits/{id}` — list/detail.
- `PUT /api/emergency-visits/{id}/status` — body `{"status": "IN_TREATMENT" | "CLOSED"}`. Legal transitions: `WAITING → IN_TREATMENT`, `WAITING → CLOSED`, `IN_TREATMENT → CLOSED`; `CLOSED` is terminal; anything else is `409`.
- `DELETE /api/emergency-visits/{id}` — service-owned and audited.

Response (`EmergencyVisitResponse`): `{id, patientId, arrivalAt, triageLevel, chiefComplaint, status}`.

Status set: `WAITING | IN_TREATMENT | CLOSED`. **`triageLevel` is a neutral demo label with no clinical meaning** — it is not ATS, ESI, MTS, or any real triage protocol, carries no assessment or prioritization semantics, and never influences any decision.

### Invoices — `/api/invoices` (financial simulation)

- `POST /api/invoices` — create with `CreateInvoiceRequest`: `patientId` (`@NotNull UUID`, must resolve or `404`), `invoiceNumber` (`@NotBlank`, unique), `amount` (`@NotNull @DecimalMin("0.00") @Digits(integer=12, fraction=2)` `BigDecimal`, stored canonically — exponent forms such as `1E+3` are echoed as plain `1000`), `currency` (`@Pattern` `[A-Z]{3}`, a demo label with no conversion, FX, or tax meaning). The server sets `status=DRAFT`. Unknown body members (including any client-supplied status) are ignored and never persisted.
- `GET /api/invoices`, `GET /api/invoices/{id}` — list/detail.
- `PUT /api/invoices/{id}/status` — legal transitions: `DRAFT → ISSUED`, `DRAFT → VOID`, `ISSUED → PAID`, `ISSUED → VOID`; `PAID` and `VOID` are terminal; anything else is `409`.
- `DELETE /api/invoices/{id}` — service-owned and audited.

Response (`InvoiceResponse`): `{id, patientId, invoiceNumber, amount, currency, status}` — `amount` is the stored canonical plain string.

Status set: `DRAFT | ISSUED | PAID | VOID`. Duplicate `invoiceNumber` → `409` (service pre-check; the DB unique constraint is the concurrency backstop and maps to a fixed generic conflict message, so SQL internals never leak). No payment gateway, tax, refund, or insurer behavior exists — this family is a demo simulation only (`/api/insurance-claims` remains an unrelated raw-CRUD family behind the same ADMIN/BILLING rule).

### Staff directory — `/api/staff`

- `GET /api/staff` — professional directory behind appointment scheduling (ADMIN/HR/RECEPTIONIST read). `StaffMemberResponse`: `{id, employeeCode, fullName, profession, licenseNumber, department}`. Writes (`POST`, `DELETE`) are ADMIN/HR.

### Audit — `/api/audit`

- `GET /api/audit` (ADMIN only) — all audit events; no ordering parameters (the frontend sorts newest-first client-side). Event: `{id, actor, action, resourceType, resourceId, details, occurredAt}` plus persistence metadata. Patient create/update, appointment create/delete, and every care-operations mutation (admission create/discharge/delete, emergency-visit create/transition/delete, invoice create/transition/delete) produce events with the acting session user; the `details` payload names the transition (e.g. `status: DISCHARGED`). Failed operations do not record events. Demo-seeded rows record their CREATE events with the `system` actor on first seed only.

### Dashboard — `/api/dashboard`

- `GET /api/dashboard` (any authenticated role) — eleven flat count keys in fixed order, aggregated by `DashboardService`:

```json
{ "patients": 3, "appointments": 2, "admissions": 2, "emergencyVisits": 3, "invoices": 4,
  "openAdmissions": 1, "activeEmergencyVisits": 2,
  "invoicesDraft": 1, "invoicesIssued": 1, "invoicesPaid": 1, "invoicesVoid": 1 }
```

The five totals count whole tables; `openAdmissions` counts `ADMITTED`, `activeEmergencyVisits` sums `WAITING` + `IN_TREATMENT`, and the four invoice keys count their respective statuses. Read-only — no audit events, no caching.

### Other endpoint families

Additional training-surface families exist behind their own `SecurityConfig` role rules: `/api/clinical-encounters`, `/api/beds`, `/api/nursing-observations`, `/api/lab-orders`, `/api/radiology-orders`, `/api/drugs`, `/api/medication-orders`, `/api/surgeries`, `/api/insurance-claims`, `/api/inventory-items`, `/api/blood-units`, `/api/diet-orders`, `/api/facility-work-orders`, `/api/documents`, `/api/notifications`, `/api/departments`, `/api/shifts`. Only the workflows above are exercised by the demonstrated journeys, the smoke script, and the frontend screens; the rest is repository CRUD with the shared error contract. Notably, `/api/beds` remains raw entity CRUD with unverified string references — it was explicitly kept out of the care-operations phase.

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
| Unknown record | `404` | `ApiError`, e.g. "Patient not found: <uuid>", "Admission not found: <uuid>", "EmergencyVisit not found: <uuid>", "Invoice not found: <uuid>" |
| Illegal lifecycle transition (admissions, emergency visits, invoices) | `409` | `ApiError`, `error: "Conflict"`, controlled client-safe message naming the record and its current state; nothing is overwritten |
| Duplicate natural key (existing `medicalRecordNumber` on `POST /api/patients`; existing `invoiceNumber` on `POST /api/invoices`) | `409` | `ApiError`, `error: "Conflict"`; message is the specific pre-check message ("Medical record number already exists" / "Invoice number already exists"), or the fixed generic message "Resource conflict: the record already exists or violates a data integrity constraint" when Spring translates a race-lost DB unique-constraint violation (SQL internals never leak) |

## Synthetic demo data (opt-in)

`MEDICORE_DEMO_SEED=true` at backend startup seeds a small synthetic cohort: 3 patients `DEMO-0001..0003`, 2 professionals `DEMO-STAFF-001..002`, 2 appointments, 2 admissions (one `ADMITTED`, one `DISCHARGED`), 3 emergency visits (one each `WAITING`, `IN_TREATMENT`, `CLOSED`, with demo triage labels), and 4 invoices `DEMO-INV-0001..0004` (one each `DRAFT`, `ISSUED`, `PAID`, `VOID`) — amounts and currency labels are display-only financial-simulation values. Default is **off**; seeding is idempotent, never deletes or modifies existing rows, creates no accounts, and records each newly created row as a CREATE audit event with the `system` actor. See `docs/runbook.md` for the exact composition and caveats.
