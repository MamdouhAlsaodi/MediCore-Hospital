# Multi-Branch Operations — Phase 3 Portfolio Evidence

> **Training/Portfolio only. Synthetic data only.** MediCore is an educational, non-clinical
> training project — **not** certified medical software. This phase demonstrates **one synthetic
> hospital organization with three synthetic branches**. It is **not** SaaS multi-tenancy, not a
> Pilot, not clinical software or clinical use, not production readiness, and it carries **no**
> capacity, SLA/SLO, real-data, or security-certification claim of any kind. Every record shown by
> this phase is unmistakably synthetic (`Demo*` / `DEMO-*` on the `synthetic.test`/`example.test`
> demo domains).

This document states exactly what the accepted Phase 3 implementation
(`docs/plan3.md`, Tasks 1–15) demonstrates, how it is evidenced, and where its deliberate limits
are. The formal acceptance map that binds every Definition-of-Done row to source, tests, browser
evidence, and live smoke lives in [`docs/traceability.md`](traceability.md) (Plan 3 section).

## 1. What Phase 3 demonstrates

### 1.1 One synthetic organization, three synthetic branches

- `HospitalOrganization → Branch → Department` hierarchy: exactly **one** organization row
  (`Demo Synthetic Hospital`, `DEMO-ORG-001`) with three active branches — `Demo Main Branch`
  (`DEMO-BR-001`, the deterministic default), `Demo North Branch` (`DEMO-BR-002`), and
  `Demo Harbor Branch` (`DEMO-BR-003`) — plus branch-owned departments with codes unique inside
  one branch (the same department code may exist on two branches, never twice inside one).
- Branch codes are unique inside the organization; all identifiers are UUIDs. Human-readable
  codes (`DEMO-BR-001`, ward/room labels, employee codes) are display/lookup values only and are
  **never** authorization evidence — the server resolves actual UUID references only.
- `GET /api/organization` returns the single organization with its active branches in
  deterministic code order; `POST /api/branches`, `GET /api/branches`, and
  `GET /api/branches/{id}` are ADMIN-only. Departments are DTO/service-owned and require a
  verified same-branch reference; a null-branch legacy department is invisible and untouchable
  through the normalized contract.

### 1.2 Identity, acting assignment, and branch-bound context

- `UserAccount` proves credentials only. Authority comes from a separate, enabled
  `ActingAssignment`: one role, one scope (`ORGANIZATION`, `BRANCH`, or `DEPARTMENT`), optional
  fixed branch/department — so an account may hold several assignments and switch between them.
- Login returns `{accessToken, tokenType, username, roles, assignments, actingContext}`:
  `roles` contains exactly the one selected acting role, `assignments` lists every currently
  valid enabled assignment as a strict allowlist view, and `actingContext` is the selected
  context. The initial token binds the deterministic first valid assignment.
- `POST /api/auth/context` (authenticated) issues a **replacement context-bound token** for a
  subject-owned assignment plus an active branch selection. Foreign, unknown, disabled, or
  inconsistent targets fail closed with one non-enumerating `403`; a missing/inconsistent
  context fails login exactly like a wrong password (`401`, non-enumerating).
- `JwtFilter` rebuilds the acting context from server state on **every** request and requires all
  structural claims to match it; the role claim is never read for authority. Disabling an
  assignment, account, or branch invalidates outstanding tokens immediately.
- The UI mirrors this server truth: the shell always displays username, acting role, and the
  server-named branch (plus department when applicable), and the acting-context selector offers
  only server-issued (assignment, branch) pairs and switches by receiving the newly issued token
  (`frontend/src/features/branches/BranchSelector.jsx`).

### 1.3 Server-enforced branch scope on every migrated workflow

Patient, staff, appointment, availability, admission, emergency-visit, invoice, and bed reads and
writes resolve only inside the acting branch:

- Create ownership is **server-stamped** from the acting context — no create request in a
  branch-scoped family carries a branch field.
- A cross-branch id answers the same generic `404` as a nonexistent record; a referenced patient,
  professional, department, or bed must belong to the acting branch. An allowed record with a
  forbidden action returns `403`; failures persist nothing and record no success audit event.
- Unassigned legacy rows (no branch ownership) are invisible and untouchable through every
  branch-scoped path; repositories use branch-aware queries — no whole-table `findAll()`/`count()`
  on branch-scoped API paths. Global uniqueness stays global (patient MRN, invoice number) by
  explicit contract.
- This is a **single-organization** scope boundary. Nothing here implements tenant resolution,
  tenant provisioning, cross-organization data sharing, or a generic tenancy framework — branches
  partition one synthetic hospital, not a SaaS.

### 1.4 Staff availability and appointment conflict prevention

- Availability is an explicit dated half-open interval (`startsAt` → `endsAt`) for one staff
  member in one branch (`POST/GET /api/staff/{id}/availability`); overlapping intervals for the
  same professional are refused with `409`, exactly-adjacent intervals are legal, and the
  professional row is pessimistically locked during the check — a bounded JPA/H2 defense, never a
  distributed-locking or production-concurrency claim.
- Appointment creation requires `durationMinutes` (5–480 minutes, an engineering validation
  range, never clinical policy), computes `endsAt` server-side, and refuses with `409` any window
  outside a containing same-branch availability interval or overlapping a non-cancelled
  appointment for that professional. Overlap is half-open: an appointment may start exactly when
  the previous one ends. No profession-, license-, or department-based clinical eligibility rule
  exists anywhere.

### 1.5 Normalized bed inventory and atomic admission lifecycle

- Beds are DTO/service-owned with normalized identity: unique
  `(branch, ward, room, bedNumber)`, statuses `AVAILABLE | OCCUPIED | MAINTENANCE |
  OUT_OF_SERVICE`. Clients create a bed with exactly `{ward, room, bedNumber}` — branch, status,
  and patient references are server-owned; `OCCUPIED` is controlled only by admissions.
  Duplicate identity, illegal transitions, maintenance of an occupied bed, deletion while
  occupied, and direct requests for `OCCUPIED` are `409`; concurrent mutations produce exactly
  one winner (DB unique constraint plus optimistic versioning).
- `patientId` is not source of truth on `Bed`: occupancy is derived from one live
  `AdmissionBedAssignment` row that exists exactly while an admission holds a bed. Admission
  creation with a bed, initial assignment, and atomic transfer (source released + target occupied
  in one transaction; any conflict rolls back both) and discharge (admission closed + bed released
  in one transaction) are each one transaction audited exactly once per successful command.
  Repeating discharge or assigning after discharge is `409` with no partial mutation.

### 1.6 Branch and network command centers

- `GET /api/dashboard/branch` returns the typed summary of the token's acting branch: the five
  workflow totals, open admissions, active emergency visits, bed counts by status, today's
  appointments (server clock), and the simulated invoice status buckets — a deterministic
  allowlist, never whole-table counts, never client-aggregated.
- `GET /api/dashboard/network` returns organization totals (exactly the sum over the returned
  per-branch summaries) plus every active branch's summary in deterministic code order, and is
  issued only to enabled ADMIN contexts with `ORGANIZATION` scope — everyone else is refused.
- `GET /api/dashboard` remains a deprecated Phase 3 compatibility alias answering exactly the
  acting branch's typed summary; it never falls back to the pre-Phase-3 whole-table flat counts.
- The UI command center renders the branch line, network comparison, and drill-downs into
  branch-scoped screens; drill-down filter state never widens server scope, which always derives
  from the token. Time contracts are ISO/UTC; branch-local time zones are a deferred owner
  decision, not invented here.

### 1.7 Branch-attributed audit evidence

- Every successful mutation records exactly one event carrying: actor, acting assignment id, role,
  scope, organization id, branch id, optional department id, action, resource type and id, the
  bounded correlation id (validated or server-generated at the request boundary and echoed in
  `X-Correlation-Id`), safe concise details, and the timestamp. Failed `400/401/403/404/409`
  operations record nothing.
- `GET /api/audit` stays ADMIN-only; within ADMIN the acting context decides the visible slice
  server-side — organization-scoped ADMIN sees the whole organization plus context-less rows
  marked `legacy/unassigned` (ownership is never guessed), branch/department-scoped ADMIN sees
  only its own slice. Filters (`branchId`, `resourceType`, `actor`, `correlationId`) apply
  conjunctively inside the scope and can never widen it.
- Details never contain bearer tokens, passwords, request bodies, stack traces, or sensitive
  personal fields.

### 1.8 Coherent three-branch synthetic cohort

With `MEDICORE_DEMO_SEED=true`, startup seeds an idempotent, referentially coherent cohort across
all three branches (organization, branches, departments, patients, professionals, dated
availability, beds in all four statuses, admissions with live bed assignments, emergency visits
with the neutral demo triage label, simulated invoices, and context-attributed `system` events).
A second seed run creates zero records and zero events; every insert is lookup-before-create on a
stable business key. Full composition and the expected review evidence: [`docs/runbook.md`](runbook.md).

## 2. How it is evidenced

| Evidence kind | Artifact |
|---|---|
| Backend suite | `mvn test` in `backend/` — 162 tests, 0 failures, including `MultiBranchOperationsApiTest` (43: hierarchy, acting context, per-workflow branch isolation, concurrency winners, legacy-row invisibility), `SecurityAuthorizationTest` (38), `DashboardApiTest` (7), `DemoDataInitializerTest` (13) |
| Frontend suites | `npm test` in `frontend/` — 237 tests across 15 files, including `multiBranchContracts.test.js`, `BranchSelector.test.jsx`, shell/page context-refresh and denial states |
| Production build | `npm run build` — Vite production bundle, exit 0 |
| Browser evidence | `npm run test:e2e` — pinned Playwright 1.61.0, real Chromium, desktop 1280×720 and mobile 375×812 journeys proving branch switching, cross-branch isolation, conflict messaging, bed assign/transfer/discharge, command-center drill-down, keyboard focus, accessible names, and no horizontal overflow; only a fully passing run publishes the screenshot set in `docs/evidence/phase3/` (inspection record in its README) |
| Live smoke | `scripts/smoke-patient-journey.sh` — the branch-aware journey (context selection → patient → availability → appointment → required 409 conflict → two beds → admit → atomic transfer → discharge/release → emergency → invoice → branch dashboard → network dashboard → filtered audit with context → second-branch isolation proof), failing on any scope leakage or partial bed mutation; dated per-step baseline in `docs/performance.md` (regression tripwire only) |
| Documentation | `docs/api.md` (endpoint/role/error contracts), `docs/architecture/multi-branch-operations.md` (layering), `docs/traceability.md` (14-row acceptance map), `docs/runbook.md` (isolated runtime procedure), `docs/implementation-status.md` (task ledger and stop gate) |

## 3. Deliberate limits (read before quoting this phase)

- **Single organization.** One organization row is the modeled universe. There is no tenant
  resolver, tenant provisioning, per-tenant configuration, or cross-organization sharing — and no
  SaaS multi-tenancy claim is made or supported.
- **Synthetic data only.** Every patient, professional, invoice, and dashboard number is a
  fabricated demo fixture. No real patient, hospital, employee, financial, or operational data
  exists anywhere in the repository or the demonstrated flows.
- **No clinical meaning.** The emergency `1–5` triage label is a neutral demo value (not ATS, ESI,
  MTS, or any protocol); appointment duration bounds are validation constants; nothing prioritizes,
  diagnoses, or advises.
- **No money movement.** Invoices are a display-only financial simulation: no payments, tax, FX,
  refunds, insurers, or collections.
- **Bounded concurrency defense.** Pessimistic locks, unique constraints, and optimistic versioning
  are tested on H2/local fixtures; they demonstrate transactional correctness of the workflow, not
  production concurrency, capacity, load, or SLA behavior.
- **Legacy rows stay hidden, not migrated.** Unknown branch-less rows remain invisible through
  scoped APIs; a real schema migration and unknown-data reconciliation are Pilot-gated and were
  deliberately not pretended here.
- **UTC contracts.** Timestamps are ISO instants/`LocalDateTime` in server UTC; branch-local time
  zones need a later explicit owner decision.
- **Untouched unrelated families.** Raw CRUD families outside the demonstrated workflows
  (clinical encounters, lab/radiology orders, drugs, medication orders, surgeries, insurance
  claims, inventory, blood units, diet orders, facility work orders, documents, notifications,
  shifts) are unchanged and remain outside the portfolio story.

## 4. Formal stop gate

Phase 3 is accepted as a **synthetic Training/Portfolio, single-organization multi-branch
demonstration** only, per the acceptance map in [`docs/traceability.md`](traceability.md). This
acceptance authorizes no Pilot, no later phase, no real data, no SaaS tenancy, no clinical use,
no payment/insurer integration, no PostgreSQL migration rehearsal, no production deployment, and
no production-readiness or capacity claim. Any such step requires the owner's explicit, separate
decision (`docs/plan3.md` §10).
