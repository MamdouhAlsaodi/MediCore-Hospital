# Multi-Branch Operations Architecture

Architecture of the Phase 3 multi-branch slice (`docs/plan3.md` Tasks 1–11), verified against
source. It extends the layering established in
[`docs/architecture/patient-journey.md`](patient-journey.md) and
[`docs/architecture/care-operations.md`](care-operations.md) with the organizational scope model:
identity/assignment/context, branch ownership, transactional bed and availability defenses, and
the cross-cutting acting-context and audit adapters.

> **Boundary:** educational, non-clinical Training/Portfolio architecture over synthetic data for
> **one** organization with multiple branches. This is not a multi-tenancy, Pilot, production, or
> capacity architecture, and no framework beyond Spring Boot/Security/JPA is introduced.

## 1. Layer map

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ UI (React 19 + Vite, no router)                                             │
│   AppShell ─ BranchSelector ─ feature pages (Patients, Appointments,        │
│   Admissions, Emergency, Invoices, Beds, Dashboard, Audit)                  │
└───────────────┬─────────────────────────────────────────────────────────────┘
                │ feature-scoped transport adapters over one shared apiFetch
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ HTTP boundary                                                               │
│   CorrelationIdFilter (cross-cutting, runs first)                           │
│   JwtFilter (cross-cutting) → rebuilds ActingContext from server state      │
│   SecurityConfig route rules → narrow @RestController / DTO mappers         │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Scoped services (workflow policy, one transaction per command)              │
│   ActingContextService · BranchAccessService · OrganizationService          │
│   PatientService · AppointmentService · StaffAvailabilityService            │
│   AdmissionService · EmergencyVisitService · InvoiceService · BedService    │
│   DashboardService                                                          │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Branch-aware Spring Data repositories (explicit branchId parameters,        │
│ pessimistic locks, grouped count projections) — no whole-table reads        │
│ on branch-scoped paths                                                      │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼
                    H2 (local profile; PostgreSQL profile deferred)

Cross-cutting adapters (not domain-policy imports):
  AuditService  — one event per successful command, acting context + bounded
                  correlation id attached; failures record nothing
  CorrelationIdFilter — validates/generates the bounded X-Correlation-Id,
                  thread-local handoff, echoed in the response header
```

Controllers stay narrow HTTP/DTO mappers; every scope, lifecycle, reference, uniqueness, and audit
rule lives in a service; repositories expose only explicit, branch-parameterized queries. The
domain never imports `SecurityContextHolder` as policy input without the fail-closed seam below,
and the acting context is consumed as a value (`ActingContext` record), never as framework
session state.

## 2. Identity, assignment, and acting context

```text
UserAccount (credentials only)
      │ 1..*
      ▼
ActingAssignment { role, scope: ORGANIZATION | BRANCH | DEPARTMENT,
                   optional fixed branch, optional department, enabled }
      │ resolved against current hierarchy state
      ▼
ActingContext { username, assignmentId, role, scope,
                organizationId, branchId, departmentId? }   ← immutable principal
```

- **Login** (`ActingContextService.login`): credential check; deterministic first valid enabled
  assignment in `(role, scope, id)` order; no valid assignment/branch ⇒ the same non-enumerating
  `401` as a wrong password. Response: `{accessToken, tokenType, username, roles, assignments,
  actingContext}` — `roles` is exactly the one acting role.
- **Context switch** (`POST /api/auth/context`): the subject owns the target assignment; ORGANIZATION
  scope selects any active branch of the organization, BRANCH scope is fixed to its own branch,
  DEPARTMENT scope derives the branch from the department. Success issues a replacement token and
  one safe `SWITCH` audit event; every refusal is one shared `403` (`BranchAccessService.RefusedException`).
- **Token**: JWT subject plus structural pointer claims (assignmentId, scope, organizationId,
  branchId, optional departmentId). The `role` claim exists for display only and is **never**
  read for authority.
- **Per-request reload** (`JwtFilter` → `ActingContextService.reload`): the structural claims are
  re-resolved against current server rows and every field must match exactly; a
  disabled/deleted account, assignment, or branch, or any tampered/stale claim, leaves the request
  unauthenticated. `JwtFilter` installs exactly one `ROLE_<acting role>` authority — never the
  union of legacy global roles.

## 3. Branch ownership and cross-branch denial

- Services own authorization: each scoped workflow service resolves the acting branch from the
  `ActingContext` principal through one fail-closed seam — no principal ⇒ `AccessDeniedException`
  (`403`), missing branch row ⇒ `AccessDeniedException`. UI hiding and repository filters are
  defense-in-depth, never the authority.
- Create ownership is server-stamped from the context; no branch-scoped create request carries a
  branch field, and no endpoint accepts a human code (branch/department/employee) as authorization
  evidence.
- Reference resolution is branch-scoped end to end: `findByIdAndBranchId`-style queries make a
  cross-branch id indistinguishable from a nonexistent one (generic `404`, no mutation, no audit
  event); same-branch rows with forbidden actions get `403`. Patient MRN and invoice number
  uniqueness remain intentionally global.
- Repositories on scoped paths expose only branch-parameterized reads (lists, details, existence
  checks, grouped count projections); whole-table `findAll()`/`count()` do not appear on them.
  Legacy null-branch rows are invisible through these queries and are never mass-adopted.
- Failure classes: invalid authentication context `401`; valid identity, refused
  assignment/branch/action `403`; hidden/cross-branch/unknown record `404`; validation/malformed
  input `400`; conflict (duplicate identity, illegal transition, scheduling conflict, unavailable
  bed) `409` — all mapped by the shared `GlobalExceptionHandler` into the stable `ApiError` body.

## 4. Transactional resources

### 4.1 Beds and admissions

```text
Bed { branchId, ward, room, bedNumber  — unique per (branch, ward, room, bedNumber)
      occupancyStatus: AVAILABLE | OCCUPIED | MAINTENANCE | OUT_OF_SERVICE, @Version }

AdmissionBedAssignment { admissionId (unique), bedId (unique) }
  — exists exactly while an admission holds a bed
```

- Bed state changes flow through narrow entity mutations with a legal-transition map; `OCCUPIED`
  is reachable only via admission commands. The DB unique constraint backstops the duplicate
  pre-check; `@Version` optimistic locking backstops concurrent claims (tested: exactly one
  winner, the loser sees the shared `409`).
- Admission with bed, initial assignment, and transfer each run in one transaction: occupy target
  ⇒ insert/move the assignment row (flushed so constraint/lock loss surfaces before audit) ⇒
  audit once. Transfer releases the source bed in the same transaction, so no intermediate state
  (two occupied beds, zero, or an orphan assignment) can commit.
- Discharge stamps `dischargedAt` server-side, deletes the assignment, releases the bed, and
  audits once — one transaction. Repeat discharge, assign-after-discharge, and unavailable targets
  are `409` with nothing persisted.

### 4.2 Availability and appointments

```text
StaffAvailability { branchId, staffMemberId, startsAt, endsAt }   — half-open interval
Appointment { branchId, patientId, professionalId, scheduledAt,
              durationMinutes (5–480), endsAt, type, status }
```

- Availability create: same-branch professional; the professional row is locked
  (`PESSIMISTIC_WRITE`) before the overlap check, so concurrent interval creates serialize — a
  bounded JPA/H2 defense, explicitly not a distributed-locking or capacity claim.
- Appointment create: computes the half-open window `[scheduledAt, scheduledAt + durationMinutes)`,
  takes a pessimistic lock on the containing availability interval, refuses an empty containment
  answer or a conflicting non-cancelled appointment (`409`), then persists and audits once.
  Adjacent windows (`newStart == existingEnd`) are legal by construction; cancelled creates carry
  no time claim. Lost lock races surface as the same `409`.
- Legacy appointments predating the computed window carry no `endsAt` and are excluded from
  conflict candidates by contract.

## 5. Command-center aggregation

- `DashboardService` builds the typed `BranchSummary` (branch identity, five workflow totals, open
  admissions, active emergency visits, four bed-status counts, today's appointments by server
  clock, four invoice buckets) exclusively from branch-restricted grouped count queries over the
  branch ids the verified context authorizes — never whole-table reads, never client aggregation.
- `NetworkSummary` = organization totals (exactly the sum over the returned branch summaries) plus
  every active branch's summary in deterministic code order; it is issued only after the service
  verifies an enabled ADMIN context with `ORGANIZATION` scope (the controller route stays
  `authenticated()`; the stricter rule lives in the service).
- `GET /api/dashboard` is a deprecated alias delegating to the branch summary during Phase 3 and
  never falls back to the historical flat whole-table contract.
- JSON key order is deterministic by record declaration order; drill-downs are UI navigation with
  filter state only — the API re-derives scope from the token on every request.

## 6. Audit and correlation as cross-cutting adapters

- Domain services call `AuditService.record(...)` once, inside the command's transaction; the
  recorder attaches the current `ActingContext` (assignment, role, scope, organization, branch,
  department) and `CorrelationIdFilter.current()` — both nullable for startup/bootstrap rows,
  which are surfaced to organization ADMIN only as `legacy/unassigned` and never guessed.
- `CorrelationIdFilter` runs before the JWT filter on every request: an inbound `X-Correlation-Id`
  is accepted only if it matches the bounded shape (`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`); anything
  else (including absent) becomes a server-generated UUID. The validated value is echoed in the
  response header, exposed thread-locally, and cleared in `finally` — so stored evidence equals
  what the caller observed. The filter never reads bodies, authenticates, or rejects.
- `GET /api/audit` is ADMIN-only at the route layer and scope-aware in `AuditController`: the
  visible slice derives from the acting context; the four filters (branch, resource type, actor,
  correlation id) intersect it and can never widen it. Rows are serialized as a strict allowlist
  view, never the entity.

## 7. Frontend structure

```text
src/
  api.js            — shared apiFetch + strict session-payload allowlist parsing
  auth.js           — sessionStorage persistence + complete-session validation
  AppShell.jsx      — identity/role/branch line, acting-context key remount,
                      role-aware navigation (navigation.js/authorization.js)
  features/
    branches/       — actingContextApi (login/context switch adapters), BranchSelector
    patients|appointments|admissions|emergency|billing|beds|staff|dashboard|audit
  DashboardPage.jsx — branch summary + network comparison + drill-downs
```

- The selector renders only server-issued (assignment, branch) pairs — ORGANIZATION assignments
  enumerate the organization's active branches from `GET /api/organization`; BRANCH/DEPARTMENT
  assignments offer exactly their fixed branch. Switching calls `POST /api/auth/context` and the
  app atomically replaces the stored session (token + context), remounting the selected screen via
  the acting-context key so no stale-branch content survives.
- No stored branch identifier is authority: a session is accepted on reload only when the whole
  server-issued structure is present and self-consistent (assignment list contains the acting
  assignment; role/scope/organization/branch agree); otherwise it is discarded to Login.
- Screens refresh scoped data on every context change; `401` clears the session, `403` renders the
  shared denial, and failure states are pinned by component tests and the Playwright journeys.

## 8. Invariants summary

1. One organization; branches unique by code inside it; departments unique by code inside a branch.
2. Authority = enabled assignment + server-rebuilt context; token claims are pointers, never powers.
3. Every branch-scoped read/write resolves through branch-parameterized repository queries; legacy
   unassigned rows are invisible; cross-branch references share the generic `404`.
4. Create ownership is server-derived; clients never choose branch, status, occupancy, discharge
   time, or computed windows.
5. Bed occupancy and admission lifecycle commit or roll back atomically; the assignment row's two
   unique constraints admit at most one active assignment per admission and per bed.
6. Scheduling refuses out-of-availability and overlapping windows transactionally; adjacency is
   legal; no clinical eligibility rule exists.
7. Exactly one audit event per successful command with full acting context and bounded correlation
   id; none on any failure; no tokens, passwords, bodies, or sensitive fields in details.
8. Network aggregation is organization-ADMIN-only and equals the sum of its branch summaries.
9. UTC/ISO time contracts; no branch-local time-zone policy is invented.
10. Public contracts are DTO allowlists — no JPA entities or persistence metadata leave the boundary.
