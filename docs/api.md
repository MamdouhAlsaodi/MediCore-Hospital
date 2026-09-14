# API Quick Start

MediCore is an educational, non-clinical training project — this API is documented for local training use only and must never carry real patient data. The invoice family below is a **financial simulation** (demo amounts and currency labels; no payments, tax, FX, or collection), and the emergency-visit triage label is a **neutral `1–5` demo value** with no clinical meaning. Phase 3 demonstrates **one synthetic organization with three synthetic branches** — every demonstrated workflow below is scoped server-side to the acting branch of an authenticated acting context. This is a single-organization scope boundary, not SaaS multi-tenancy.

## Base and auth

1. Start the backend on loopback port `5501` (`server.address: 127.0.0.1`, `server.port: 5501` in `backend/src/main/resources/application.yml`). Boot commands are in `docs/runbook.md`.
2. `POST /api/auth/login` with `{"username":"admin","password":"<value of HOSPITAL_ADMIN_PASSWORD supplied at backend startup>"}`.
3. Send the returned access token in the authenticated request header on every call.

The admin password is never stored in this repository. Set `HOSPITAL_ADMIN_PASSWORD` in the runtime environment (at least 12 characters) before first start; the initial `admin` account is created only when it does not exist yet, and an existing account is never modified. `HOSPITAL_JWT_SECRET` is also required from the environment.

Login response (`ActingContextService.Session` — exactly these fields):

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

Identity and authority are separate: the account proves the credentials, while every scrap of authority comes from an enabled `ActingAssignment` (one role, one scope `ORGANIZATION | BRANCH | DEPARTMENT`, optional fixed branch/department). Login binds the deterministic first valid enabled assignment; `roles` contains exactly the one selected acting role. An account without a valid enabled assignment and eligible active branch cannot log in — the refusal is the same non-enumerating `401` as a wrong password.

**Acting-context switch** — `POST /api/auth/context` (authenticated) with `{"assignmentId":"<uuid>","branchId":"<optional uuid>"}`. The assignment must belong to the authenticated subject; `ORGANIZATION` scope requires an active branch selection for branch APIs, `BRANCH` scope is fixed to its own branch, and `DEPARTMENT` scope derives the branch. Success returns the same `Session` shape with a **replacement context-bound token** and records one safe `SWITCH` audit event. A foreign, unknown, disabled, or inconsistent target fails closed with `403` `ApiError` (message `The selected assignment or branch is not available.`) — no existence information leaks. The `JwtFilter` rebuilds the acting context from server state on every request, so disabled/deleted assignments or branches invalidate outstanding tokens immediately.

Rejected logins return `401` with a uniform, non-enumerating body: `{"error":"Invalid username or password."}`.

## Roles and scopes

Accounts hold `ActingAssignment`s over roles from `Role` (`ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST`, `LAB_TECH`, `RADIOLOGY_TECH`, `PHARMACIST`, `BILLING`, `HR`, `STAFF`). Authorization is enforced server-side in `SecurityConfig` from the per-request re-derived acting role — never from a client-sent role string. Public endpoints are only `POST /api/auth/login` and `/actuator/health`; `/api/auth/**` is otherwise authenticated; explicit family rules apply, ordered before an ADMIN-only catch-all for unmatched `/api/**` (default deny). The organization/branch surface (`/api/organization/**`, `/api/branches/**`) is ADMIN-only, department create/delete are ADMIN-only hierarchy writes (department read keeps its ADMIN/HR family role), and `/api/dashboard/**` stays reachable for every authenticated role while `/api/dashboard/network` enforces its stricter organization-scoped ADMIN contract in `DashboardService`. Unauthenticated requests get `401`; an authenticated role without permission gets `403`.

### Enforced role matrix — demonstrated workflows

Method-level rules (ordered before the family rules) make the write policy server-enforced, not UI-only:

| Endpoint | ADMIN | DOCTOR | NURSE | RECEPTIONIST | BILLING |
|---|---|---|---|---|---|
| `GET /api/patients`, `GET /api/patients/{id}` | 2xx | 2xx | 2xx | 2xx | 403 |
| `POST /api/patients` | 2xx | 403 | 403 | 2xx | 403 |
| `PUT /api/patients/{id}` | 2xx | 403 | 403 | 2xx | 403 |
| `GET /api/appointments` | 2xx | 2xx | 2xx | 2xx | 403 |
| `POST /api/appointments` | 2xx | 403 | 403 | 2xx | 403 |
| `/api/admissions/**` (all methods, incl. `POST`, `PUT …/bed`, `PUT …/status`, `DELETE`) | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/emergency-visits/**` (all methods) | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/beds/**` (all methods) | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/invoices/**` (all methods, incl. `POST`/`PUT …/status`/`DELETE`) | 2xx | 403 | 403 | 403 | 2xx |
| `GET /api/staff/**` (incl. `…/availability`) | 2xx | 403 | 403 | 2xx | 403 |
| `POST /api/staff/{id}/availability` | 2xx | 403 | 403 | 403 (HR: 2xx) | 403 |
| `POST/DELETE /api/staff/*`, `/api/departments/**` (GET), `/api/shifts/**` | 2xx | 403 | 403 | 403 (HR: 2xx) | 403 |
| `POST/DELETE /api/departments/**` (hierarchy writes) | 2xx | 403 | 403 | 403 | 403 (HR: 403) |
| `/api/organization/**`, `/api/branches/**` | 2xx | 403 | 403 | 403 | 403 |
| `GET /api/audit` | 2xx | 403 | 403 | 403 | 403 |
| `GET /api/dashboard/branch` (and alias) | 2xx | 2xx | 2xx | 2xx | 2xx (any authenticated) |
| `GET /api/dashboard/network` | 2xx (ORGANIZATION-scope ADMIN only, enforced in service) | 403 | 403 | 403 | 403 |

Pinned by `SecurityAuthorizationTest` (38 tests, including the hierarchy ADMIN-only rules and the care-operations family matrices). The admission/emergency/bed/invoice family rules admit or refuse every remaining configured role identically; the matrix above shows the roles the demonstrated workflows use.

## Demonstrated workflow endpoints

All reads and writes below resolve only inside the acting branch of the token's acting context. Ownership on create is server-stamped from the context — no create request carries a branch field. A cross-branch id answers the same generic `404` as a nonexistent record; unassigned legacy rows (no branch ownership) are invisible through these paths.

### Organization and branches — `/api/organization`, `/api/branches` (ADMIN only)

- `GET /api/organization` — the single organization with only its active branches in deterministic code order: `{id, code, name, activeBranches: [{id, organizationId, code, name, locationLabel, active}]}` (`OrganizationDtos`).
- `POST /api/branches` — `{code, name, locationLabel}` (trimmed, nonblank); duplicate code inside the organization → `409` (pre-check plus the `(organization_id, code)` DB unique constraint backstop); `201` with the branch allowlist DTO.
- `GET /api/branches`, `GET /api/branches/{id}` — ADMIN-only list/detail in deterministic order. Human codes are display values, never authorization evidence.

### Departments — `/api/departments`

Normalized DTO/service contract (`DepartmentService`): every department requires a verified **active** branch, codes are unique inside a branch (the same code on two branches is legal), responses are `{id, branchId, code, name, specialty, location}` allowlists, and create/delete are ADMIN-only hierarchy writes audited once per success. `GET /api/departments?branchId=` lists assigned departments only — without a filter it covers every assigned row (this hierarchy surface is route-authorized ADMIN/HR, not acting-branch-scoped), a supplied unknown branch is the shared `404`, and unassigned legacy rows are never disclosed on any route. Duplicate code inside one branch → `409`.

### Patients — `/api/patients`

- `POST /api/patients` — create. Body (`CreatePatientRequest`): required `medicalRecordNumber`, `fullName`; optional `dateOfBirth` (`yyyy-MM-dd`), `sex`, `phone`, `email` (`@Email`-validated), `nationalId`, `address`. Blank `dateOfBirth` is accepted as `null`. The record is owned by the acting branch.
- `GET /api/patients?q=<query>` — list; `q` filters by `fullName` containing the query, case-insensitive; omitted/blank returns all. Branch-scoped: only the acting branch's patients, ever.
- `GET /api/patients/{id}` — detail by UUID; cross-branch/legacy id → generic `404`.
- `PUT /api/patients/{id}` — update. Body (`UpdatePatientRequest`) permits exactly `{fullName, phone, email, address}`; MRN, date of birth, sex, and national ID are immutable through this contract.

Every response is a `PatientResponse`:

```json
{ "id": "uuid", "branchId": "<acting branch uuid>", "medicalRecordNumber": "…", "fullName": "…",
  "dateOfBirth": "1980-01-01", "sex": "…", "phone": "…", "email": "…", "nationalId": "…",
  "address": "…", "active": true }
```

Duplicate `medicalRecordNumber` on create is rejected with `409 Conflict` — a pre-check in `PatientService.create` throws before any save, and a race-lost database unique-constraint violation is also mapped to `409` (MRN uniqueness is global by explicit contract; see the error contract below).

### Appointments — `/api/appointments`

- `POST /api/appointments` — create with `CreateAppointmentRequest`: `patientId`, `professionalId` (`@NotNull UUID`, both must resolve **inside the acting branch** or `404`), `scheduledAt` (ISO `LocalDateTime`), `durationMinutes` (`@Min(5) @Max(480)` — a bounded engineering validation range, never clinical policy), `type` (`@NotBlank`), `status` (`@Pattern`: `scheduled|confirmed|completed|cancelled`). The server computes `endsAt = scheduledAt + durationMinutes` and enforces the availability contract transactionally: the window must sit inside one containing availability interval of that same-branch professional, and must not overlap a non-cancelled appointment for that professional — either violation is `409` with a controlled conflict message and nothing persisted. Adjacent windows (one ends exactly when the next starts) are legal. A `cancelled` create carries no time claim.
- `GET /api/appointments` — branch-scoped list; `GET /api/appointments/{id}` — detail; `DELETE /api/appointments/{id}` — delete (four-role family rule; service-side reference and audit rules still apply).

Response (`AppointmentResponse`): `{id, branchId, patientId, professionalId, scheduledAt, durationMinutes, endsAt, type, status}` — references are canonical UUID strings; legacy pre-Phase-3 rows carry `branchId: null` and are never disclosed. Create and delete are atomic with their audit events (`AppointmentService`).

### Staff availability — `/api/staff/{id}/availability`

- `POST /api/staff/{id}/availability` (ADMIN/HR) — `{startsAt, endsAt}` dated half-open interval for one verified same-branch professional; overlapping intervals for the same professional → `409`, exactly-adjacent intervals legal. The professional row is pessimistically locked during the check (a bounded JPA/H2 defense, not a distributed-locking or capacity claim); a lost lock race surfaces as the shared `409`.
- `GET /api/staff/{id}/availability?from=&to=` (ADMIN/HR/RECEPTIONIST) — allowlisted intervals `{id, branchId, staffMemberId, startsAt, endsAt}` inside the window, branch-scoped. No recurring calendar, leave, payroll, credentialing, or profession-based clinical-eligibility concept exists.

### Admissions — `/api/admissions`

- `POST /api/admissions` — create with `CreateAdmissionRequest`: `patientId` (`@NotNull UUID`, must resolve inside the acting branch or `404`), optional `bedId` (must be an `AVAILABLE` bed of the acting branch), `admittedAt` (ISO `LocalDateTime`), `reason` (`@NotBlank`, trimmed). No status field — the server sets `status=ADMITTED`; with a bed, occupancy and the live assignment row are created in the same transaction.
- `GET /api/admissions`, `GET /api/admissions/{id}` — branch-scoped list/detail.
- `PUT /api/admissions/{id}/bed` — body `{"bedId":"<uuid>"}`: initial assignment or **atomic transfer** for an `ADMITTED` admission. The target must be an `AVAILABLE` bed of the acting branch and different from the held bed; the transfer releases the source bed and occupies the target in one transaction, and any failure rolls back both.
- `PUT /api/admissions/{id}/status` — body `{"status":"DISCHARGED"}`: the only defined transition, legal only from `ADMITTED`. The server stamps `dischargedAt` and releases the held bed in the same transaction; the client cannot supply the time.
- `DELETE /api/admissions/{id}` — service-owned; releases an active assignment like discharge.

Response (`AdmissionResponse`) — exactly these fields, no persistence metadata:

```json
{ "id": "uuid", "branchId": "<acting branch uuid>", "patientId": "<canonical patient uuid string>",
  "admittedAt": "2026-01-15T10:00:00", "dischargedAt": null, "reason": "demo synthetic admission",
  "status": "ADMITTED",
  "currentBed": { "bedId": "<uuid>", "ward": "Demo Ward A", "room": "101", "bedNumber": "01" } }
```

`currentBed` is present exactly while a live assignment holds a bed. Status set: `ADMITTED | DISCHARGED`. Repeating a discharge, assigning after discharge, or targeting an unavailable/foreign bed returns `409` with a controlled message and no partial mutation or audit event.

### Emergency visits — `/api/emergency-visits`

- `POST /api/emergency-visits` — create with `CreateEmergencyVisitRequest`: `patientId` (`@NotNull UUID`, must resolve inside the acting branch or `404`), `arrivalAt` (ISO `LocalDateTime`, canonical string), `triageLevel` (`@Pattern` `1|2|3|4|5`), `chiefComplaint` (`@NotBlank`, trimmed). The server sets `status=WAITING` and stamps the acting branch as owner.
- `GET /api/emergency-visits`, `GET /api/emergency-visits/{id}` — branch-scoped list/detail.
- `PUT /api/emergency-visits/{id}/status` — body `{"status": "IN_TREATMENT" | "CLOSED"}`. Legal transitions: `WAITING → IN_TREATMENT`, `WAITING → CLOSED`, `IN_TREATMENT → CLOSED`; `CLOSED` is terminal; anything else is `409`.
- `DELETE /api/emergency-visits/{id}` — service-owned and audited.

Response (`EmergencyVisitResponse`): `{id, branchId, patientId, arrivalAt, triageLevel, chiefComplaint, status}`.

Status set: `WAITING | IN_TREATMENT | CLOSED`. **`triageLevel` is a neutral demo label with no clinical meaning** — it is not ATS, ESI, MTS, or any real triage protocol, carries no assessment or prioritization semantics, and never influences any decision.

### Invoices — `/api/invoices` (financial simulation)

- `POST /api/invoices` — create with `CreateInvoiceRequest`: `patientId` (`@NotNull UUID`, must resolve inside the acting branch or `404`), `invoiceNumber` (`@NotBlank`, globally unique), `amount` (`@NotNull @DecimalMin("0.00") @Digits(integer=12, fraction=2)` `BigDecimal`, stored canonically — exponent forms such as `1E+3` are echoed as plain `1000`), `currency` (`@Pattern` `[A-Z]{3}`, a demo label with no conversion, FX, or tax meaning). The server sets `status=DRAFT` and stamps the acting branch as owner. Unknown body members (including any client-supplied status) are ignored and never persisted.
- `GET /api/invoices`, `GET /api/invoices/{id}` — branch-scoped list/detail.
- `PUT /api/invoices/{id}/status` — legal transitions: `DRAFT → ISSUED`, `DRAFT → VOID`, `ISSUED → PAID`, `ISSUED → VOID`; `PAID` and `VOID` are terminal; anything else is `409`.
- `DELETE /api/invoices/{id}` — service-owned and audited.

Response (`InvoiceResponse`): `{id, branchId, patientId, invoiceNumber, amount, currency, status}` — `amount` is the stored canonical plain string.

Status set: `DRAFT | ISSUED | PAID | VOID`. Duplicate `invoiceNumber` → `409` (service pre-check; the DB unique constraint is the concurrency backstop with a fixed generic conflict message, so SQL internals never leak). No payment gateway, tax, refund, or insurer behavior exists — this family is a demo simulation only (`/api/insurance-claims` remains an unrelated raw-CRUD family behind the same ADMIN/BILLING rule).

### Beds — `/api/beds` (normalized inventory, Task 6)

- `POST /api/beds` — body is exactly `{ward, room, bedNumber}` (trimmed, nonblank). Branch comes from the acting context, status starts `AVAILABLE`; no branch, status, or patient input is accepted. Duplicate `(branch, ward, room, bedNumber)` → `409` (pre-check plus the DB unique constraint).
- `GET /api/beds`, `GET /api/beds/{id}` — branch-scoped DTOs: `{id, branchId, ward, room, bedNumber, occupancyStatus}`. No persistence metadata and no legacy raw patient reference is exposed.
- `PUT /api/beds/{id}/status` — only legal transitions among `AVAILABLE`, `MAINTENANCE`, and `OUT_OF_SERVICE` (an unoccupied bed only); a request for `OCCUPIED` is `409` because occupancy is admission-owned. Illegal transitions and maintenance of an occupied bed are `409`.
- `DELETE /api/beds/{id}` — allowed only while unoccupied; otherwise `409`.

`patientId` is not part of the public contract: current occupancy is derived from the live admission-bed assignment (`docs/architecture/multi-branch-operations.md` §4.1).

### Staff directory — `/api/staff`

- `GET /api/staff` — professional directory behind appointment scheduling (ADMIN/HR/RECEPTIONIST read). `StaffMemberResponse`: `{id, branchId, employeeCode, fullName, profession, licenseNumber, department}` — branch-scoped to the acting context. Writes (`POST`, `DELETE`) are ADMIN/HR.

### Audit — `/api/audit`

- `GET /api/audit` (ADMIN only) — scope-aware evidence read with optional conjunctive filters `?branchId=&resourceType=&actor=&correlationId=`. Within ADMIN, the acting context decides the visible slice server-side: an `ORGANIZATION`-scoped ADMIN inspects the whole organization plus context-less rows marked `"branchAttribution": "legacy/unassigned"` (ownership is never guessed); a `BRANCH`-scoped ADMIN sees only its branch; a `DEPARTMENT`-scoped ADMIN only its department. Filters intersect the slice — a foreign branch filter answers an empty set, never a widened list. Reads record no audit events.
- Event view (strict allowlist, never the entity):

```json
{ "id": "uuid", "actor": "admin", "action": "CREATE", "resourceType": "Patient",
  "resourceId": "<uuid>", "details": "<safe concise transition label>",
  "occurredAt": "2026-09-13T12:00:00Z", "assignmentId": "<uuid>", "role": "ADMIN",
  "scope": "ORGANIZATION", "organizationId": "<uuid>", "branchId": "<uuid>",
  "departmentId": null, "correlationId": "<bounded id>", "branchAttribution": null }
```

Every successful mutation produces exactly one event with the full acting context and the bounded correlation id (validated or server-generated at the request boundary and echoed in `X-Correlation-Id`); failed `400/401/403/404/409` operations record none. Details never contain tokens, passwords, request bodies, stack traces, or sensitive personal fields.

### Dashboard — `/api/dashboard/branch`, `/api/dashboard/network`

- `GET /api/dashboard/branch` (any authenticated role) — typed summary of the token's acting branch, `DashboardDtos.BranchSummary`:

```json
{ "branchId": "uuid", "branchCode": "DEMO-BR-001", "branchName": "Demo Main Branch",
  "patients": 3, "appointments": 2, "admissions": 2, "emergencyVisits": 3, "invoices": 4,
  "openAdmissions": 1, "activeEmergencyVisits": 2,
  "bedsAvailable": 1, "bedsOccupied": 1, "bedsMaintenance": 1, "bedsOutOfService": 1,
  "todayAppointments": 0,
  "invoicesDraft": 2, "invoicesIssued": 2, "invoicesPaid": 2, "invoicesVoid": 1 }
```

Every number counts only rows the acting branch owns; the shape is a deterministic allowlist (record declaration order), aggregated by branch-restricted grouped count queries — never whole-table reads.

- `GET /api/dashboard/network` — organization totals plus `branches: [BranchSummary…]` (every active branch in deterministic code order, including all-zero summaries). Totals equal exactly the sum over the returned per-branch summaries. Issued only to enabled ADMIN contexts with `ORGANIZATION` scope; every other authenticated context is refused.
- `GET /api/dashboard` — deprecated Phase 3 compatibility alias answering exactly the acting branch's typed summary; it never falls back to the pre-Phase-3 whole-table flat counts. New clients must call `/api/dashboard/branch`.

Read-only — no audit events, no caching. The demo fixtures are dated 2031, so `todayAppointments` honestly stays zero under the seed.

### Other endpoint families

Additional training-surface families exist behind their own `SecurityConfig` role rules: `/api/clinical-encounters`, `/api/nursing-observations`, `/api/lab-orders`, `/api/radiology-orders`, `/api/drugs`, `/api/medication-orders`, `/api/surgeries`, `/api/insurance-claims`, `/api/inventory-items`, `/api/blood-units`, `/api/diet-orders`, `/api/facility-work-orders`, `/api/documents`, `/api/notifications`, `/api/shifts`. Only the workflows above are exercised by the demonstrated journeys, the smoke script, and the frontend screens; the rest is repository CRUD with the shared error contract and is unchanged by Phase 3. (`/api/beds` and `/api/departments` graduated from this list in Phase 3 — see their sections above.)

## Error contract

Client errors are mapped in one place, `shared/GlobalExceptionHandler`, always producing the stable `ApiError` JSON shape:

```json
{ "timestamp": "2026-09-13T12:00:00Z", "status": 400, "error": "Validation Error",
  "message": "fullName: must not be blank", "path": "/api/patients" }
```

| Situation | Status | Body |
|---|---|---|
| No/expired session, or a token whose acting context no longer resolves | `401` | `{"error":"authentication required"}` (security entry point) |
| Role refused | `403` | `{"error":"access denied"}` (access-denied handler) |
| Rejected login | `401` | `{"error":"Invalid username or password."}` |
| Context-switch refusal (foreign/unknown/disabled assignment or inactive branch) | `403` | `ApiError`, `error: "Forbidden"`, message `The selected assignment or branch is not available.` |
| Validation failure (`@Valid`) | `400` | `ApiError`, `error: "Validation Error"`, message is a comma-joined field summary |
| Malformed path UUID | `400` | `ApiError`, "Invalid path value: <name>" |
| Malformed request body (bad JSON, non-UUID reference, unparseable date/time) | `400` | `ApiError`, "Malformed request body" |
| Unknown **or cross-branch** record (patients, staff, departments, appointments, admissions, beds, emergency visits, invoices — legacy unassigned rows included) | `404` | `ApiError`, generic safe message naming the type, e.g. "Patient not found: <uuid>" — cross-branch access is indistinguishable from a missing record |
| Illegal lifecycle transition (admissions, emergency visits, invoices, beds) | `409` | `ApiError`, `error: "Conflict"`, controlled client-safe message naming the record and its current state; nothing is overwritten |
| Scheduling conflict (appointment outside availability or overlapping; overlapping availability intervals; lost availability lock race) | `409` | `ApiError`, `error: "Conflict"`, controlled message (e.g. "Scheduling conflict: the appointment overlaps an existing appointment for this professional"); nothing is persisted |
| Duplicate natural key (global `medicalRecordNumber` on `POST /api/patients`; global `invoiceNumber` on `POST /api/invoices`; per-branch bed identity; per-organization branch code; per-branch department code) | `409` | `ApiError`, `error: "Conflict"`; specific pre-check message where defined, or the fixed generic message "Resource conflict: the record already exists or violates a data integrity constraint" when Spring translates a race-lost DB unique-constraint violation (SQL internals never leak) |

## Synthetic demo data (opt-in)

`MEDICORE_DEMO_SEED=true` at backend startup seeds an idempotent synthetic cohort across one organization and its three branches: 4 branch-owned departments, 6 patients `DEMO-0001..0006`, 5 professionals `DEMO-STAFF-…`, 4 appointments inside dated availability intervals, 8 beds covering all four statuses, 4 admissions (2 `ADMITTED` with live bed assignments, 2 `DISCHARGED`), 5 emergency visits (2 `WAITING`, 1 `IN_TREATMENT`, 2 `CLOSED`, with demo triage labels), and 7 simulated invoices `DEMO-INV-…` (2 each `DRAFT`/`ISSUED`/`PAID`, 1 `VOID`) — amounts and currency labels are display-only financial-simulation values. Default is **off**; seeding is lookup-before-create and idempotent (a second run creates zero records and zero events), never deletes or modifies existing rows, creates no accounts, and records each newly created row as a CREATE audit event with the `system` actor (plus the 2 admission bed-assignment `UPDATE Admission` actions — 54 events for the full first run). See `docs/runbook.md` for the exact composition and caveats.
