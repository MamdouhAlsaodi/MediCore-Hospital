# The Demonstrated Care-Operations Journey

> **Training/Portfolio only. Synthetic data only.** MediCore is an educational, non-clinical
> training project — **not** certified medical software. Nothing here is clinical decision
> support, medical advice, or a real triage protocol. The invoice family is a **financial
> simulation**: no payment processing, tax, FX, or collection exists or is implied. No real
> hospital data is used anywhere, and nothing in this document is a production, Pilot, capacity,
> or multi-tenancy claim. Phase 3 demonstrates **one synthetic organization with three synthetic
> branches** — every step below happens inside the acting branch of an authenticated acting
> context.

This document tells the care-operations story the Phase 2 milestone (docs/plan2.md) introduced and
the Phase 3 milestone (docs/plan3.md) made branch-aware and bed-integrated: a synthetic patient
gets an appointment, is admitted into an available bed, is transferred atomically, and is
discharged with the bed released; walks through an emergency visit with a neutral demo triage
label; and receives a simulated invoice — with every step reference-checked, branch-scoped,
lifecycle-validated, role-gated, and audited. Every claim was verified against source and is
pinned by automated tests; `docs/traceability.md` is the evidence map and `docs/api.md` the full
endpoint reference.

## Before you start

Start a disposable local backend with the opt-in three-branch synthetic demo cohort
(`MEDICORE_DEMO_SEED=true`) so patient, professional, and bed references resolve — exact commands
and required environment variable **names** are in `docs/runbook.md`; values live only in the
runtime environment. Log in as an ADMIN account provisioned via `HOSPITAL_ADMIN_PASSWORD` (ADMIN
can perform every step below within its acting context; the role boundary table says who else
can). The branch command center is the default screen for every context; the network comparison
appears only for `ORGANIZATION`-scope ADMIN.

## The journey at a glance

| Step | What happens | Server-owned outcome | Who |
|---|---|---|---|
| Patient + appointment | Phase 1 journey, branch-scoped: register, search, availability-aware schedule | Verified same-branch references; conflict-free window | ADMIN, RECEPTIONIST (writes) |
| Admit | Register an admission for the same-branch patient, optionally into an available bed | `status=ADMITTED`; bed occupied and assignment row created in one transaction | ADMIN, DOCTOR, NURSE, RECEPTIONIST |
| Assign/transfer bed | `PUT /api/admissions/{id}/bed` | Atomic transfer: source released + target occupied, or full rollback | same four roles |
| Discharge | Transition the admission | `status=DISCHARGED`, server-stamped `dischargedAt`, bed released — one transaction | same four roles |
| Emergency visit | Register a visit with a neutral `1–5` demo triage label | `status=WAITING` | same four roles |
| Visit transitions | `WAITING → IN_TREATMENT → CLOSED` | Server-validated transitions | same four roles |
| Invoice | Create a uniquely numbered simulated invoice | `status=DRAFT` | ADMIN, BILLING only |
| Invoice lifecycle | `DRAFT → ISSUED → PAID` (or `VOID` at either step) | Server-validated transitions | ADMIN, BILLING only |
| Branch command center | Read the acting branch's typed summary | Deterministic branch-scoped counts | any authenticated context |
| Network comparison | Compare all three branches | Organization totals = exact sum of per-branch summaries | `ORGANIZATION`-scope ADMIN only |
| Audit | Inspect the evidence trail | One context-attributed event per successful mutation | ADMIN only (scope-aware slice) |

## Step 1 — Existing synthetic patient and appointment

The journey starts from a patient that already exists **in the acting branch**: either a
`DEMO-0001..0006` record from the opt-in seed (three on the main branch, two north, one harbor) or
a record created through the Phase 1 registration flow in the same branch. The seed also provides
the same-branch professional references (`DEMO-STAFF-…`) and dated availability intervals that
appointment scheduling resolves, plus branch-owned beds. Registration, search, detail, edit, and
availability-aware scheduling behavior are documented in `docs/patient-journey.md` and are not
repeated here. Cross-branch ids answer the generic `404` everywhere below — a northern patient
simply does not exist from the main branch.

## Step 2 — Admission with bed assignment, atomic transfer, and server-stamped discharge

- **Who:** `ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST` (server family rule on `/api/admissions/**`;
  the UI offers the Admissions destination to exactly these roles).
- **API:** `POST /api/admissions` with `{patientId, admittedAt, reason}` and the optional
  `bedId` — the create request deliberately carries **no** status or branch field. The service
  resolves `patientId` inside the acting branch (unknown or cross-branch → the generic `404`);
  with a bed, the bed must be an `AVAILABLE` bed of the same branch, and occupancy plus the live
  assignment row are created in the same transaction that saves the admission.
- **Bed assignment/transfer:** `PUT /api/admissions/{id}/bed` with `{"bedId":…}` — initial
  assignment or atomic transfer for an `ADMITTED` admission. The transfer releases the source bed
  and occupies the target inside one transaction; a target conflict rolls back both changes with
  `409` and no partial mutation. The admission response carries the branch id and the
  `currentBed` summary exactly while a bed is held.
- **Discharge:** `PUT /api/admissions/{id}/status` with `{"status":"DISCHARGED"}` — the only
  legal transition, legal only from `ADMITTED`. The **server stamps `dischargedAt` and releases
  the held bed in the same transaction**; the client cannot supply the time, and discharging an
  already-discharged admission or assigning a bed after discharge is refused with `409` and no
  partial mutation.
- **UI:** the Admissions screen lists branch-scoped admissions with status badges and the
  currently held bed, offers a register form with patient and optional bed selection, and guarded
  assign/transfer/discharge actions; Patient detail offers "Register admission" for the
  preselected patient. The mobile browser journey drives the full lifecycle (register without a
  bed → assign → discharge the seeded admission releasing its bed → transfer into the released
  bed) and commits it as committed evidence.
- **Concurrency:** two admissions claiming one bed produce exactly one winner (DB unique
  constraints on the assignment row plus bed optimistic locking); the loser receives the shared
  `409`.

Failure examples: unknown or cross-branch `patientId`/`bedId` → `404`; blank `reason` or malformed
body → `400`; non-`AVAILABLE` target bed, repeat discharge, repeat assignment of the same bed →
`409`; `BILLING` calling any admissions route → `403`; no/expired session or invalidated context →
`401`.

## Step 3 — Emergency visit with a neutral triage label

- **Who:** the same four roles (server family rule on `/api/emergency-visits/**`).
- **API:** `POST /api/emergency-visits` with `{patientId, arrivalAt, triageLevel, chiefComplaint}`.
  The patient must resolve inside the acting branch (otherwise the generic `404`), the visit is
  owned by the acting branch, and `triageLevel` is a **neutral demo label restricted to `1`–`5`**
  by a pattern-validated contract. It is not ATS, ESI, MTS, or any real triage protocol, carries
  no assessment or prioritization semantics, and never influences any decision. The server sets
  `status=WAITING`.
- **Transitions:** `PUT /api/emergency-visits/{id}/status` — the only legal paths are
  `WAITING → IN_TREATMENT`, `WAITING → CLOSED`, and `IN_TREATMENT → CLOSED`; `CLOSED` is
  terminal. Anything else is refused with `409`. Lists, details, transitions, and deletes resolve
  only inside the acting branch; unassigned legacy rows are invisible.

Failure examples: triage label `6` or non-numeric → `400`; cross-branch `patientId` → `404`;
`CLOSED → IN_TREATMENT` → `409`; `BILLING` → `403`.

## Step 4 — Invoice financial simulation

- **Who:** `ADMIN` and `BILLING` **only** — `SecurityConfig` maps `/api/invoices/**` to these two
  roles on every method, so RECEPTIONIST, DOCTOR, and NURSE receive `403` on reads and writes
  alike, and the UI offers the Invoices destination to no one else.
- **API:** `POST /api/invoices` with `{patientId, invoiceNumber, amount, currency}` — again no
  client-supplied status, and the patient must resolve inside the acting branch. `amount` is a
  non-negative demo value with at most 12 integer and 2 fraction digits, stored canonically (an
  exponent-shaped `1E+3` is echoed back as plain `1000`); `currency` is a three-letter uppercase
  **demo label** with no conversion or FX meaning. The server sets `status=DRAFT` and stamps the
  acting branch as owner.
- **Uniqueness:** `invoiceNumber` must be unique (globally, by explicit contract) — a service
  pre-check returns `409` without overwriting the original, and a database unique constraint is
  the concurrency backstop (a race-lost violation maps to a fixed generic conflict message; SQL
  internals never leak).
- **Lifecycle:** `DRAFT → ISSUED`, `DRAFT → VOID`, `ISSUED → PAID`, `ISSUED → VOID`; `PAID` and
  `VOID` are terminal. Illegal, repeated, or backward transitions are refused with `409`. Lists,
  details, transitions, and deletes are branch-scoped.

The invoice screen states on-page: *financial simulation — no real payments. Amounts and currency
labels are demo values with no conversion, FX, or tax meaning.* No payment gateway, tax,
regulatory, refund, or insurance-claim behavior exists anywhere in the project.

## Step 5 — Branch command center and network comparison

- **Branch summary** — `GET /api/dashboard/branch` (any authenticated context) returns the typed
  summary of the acting branch only: the five workflow totals, open admissions, active emergency
  visits, the four bed-status counts, today's appointments (server clock; the 2031-dated fixtures
  honestly keep this at zero), and the four simulated invoice buckets. Deterministic allowlist
  shape; branch-restricted aggregated queries only — never whole-table counts.
- **Network comparison** — `GET /api/dashboard/network` returns the organization totals (exactly
  the sum over the returned per-branch summaries) plus every active branch in deterministic code
  order. It is served **only** to enabled ADMIN contexts with `ORGANIZATION` scope; every other
  authenticated context is refused.
- **Alias** — `GET /api/dashboard` remains a deprecated Phase 3 compatibility alias answering
  exactly the acting branch's typed summary; it never falls back to the pre-Phase-3 flat
  whole-table contract.
- **UI:** the Dashboard screen shows the acting-branch line, the summary cards, and — for
  organization ADMIN — the network comparison with per-branch drill-downs into the branch-scoped
  screens; drill-down filter state never widens server scope, which always derives from the token.

## Step 6 — Scope-aware audit visibility

Every successful mutation in the journey writes **exactly one** audit event with the acting
username, the acting assignment id, role, and scope, the organization and branch ids (plus
department when applicable), the bounded correlation id (validated or server-generated at the
request boundary and echoed in `X-Correlation-Id`), a safe concise `details` label naming the
transition (for example `status: DISCHARGED` or `bed: <uuid>`), and the timestamp: admission
create, bed assignment/transfer, discharge, emergency-visit create and transitions, invoice create
and transitions, and delete operations. Failed operations — `400` validation, `404` unknown or
cross-branch reference, `409` conflict, `403` refusal, `401` invalid context — persist nothing and
record **no** event.

`GET /api/audit` stays ADMIN-only; within ADMIN the acting context decides the visible slice
server-side — an `ORGANIZATION`-scope ADMIN inspects the whole organization plus context-less rows
marked `legacy/unassigned` (ownership is never guessed), a branch-scoped ADMIN only its branch's
rows, and a department-scoped ADMIN only its department's. The optional filters (`branchId`,
`resourceType`, `actor`, `correlationId`) apply conjunctively inside the slice and can never widen
it. Event details never contain tokens, passwords, request bodies, stack traces, or sensitive
personal fields. Seeded records record their CREATE events with the `system` actor on first seed
only — 54 events for the full three-branch cohort (composition in `docs/runbook.md`), none on
re-seed.

## Role boundaries

The server is the only authority; the frontend permission map is a convenience that mirrors it so
the interface never offers what the backend refuses.

| Family | ADMIN | DOCTOR | NURSE | RECEPTIONIST | BILLING |
|---|---|---|---|---|---|
| `/api/admissions/**` (incl. `…/bed`) | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/emergency-visits/**` | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/beds/**` | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/invoices/**` | 2xx | 403 | 403 | 403 | 2xx |
| `/api/dashboard/branch` (and alias) | 2xx | 2xx | 2xx | 2xx | 2xx |
| `/api/dashboard/network` | 2xx (`ORGANIZATION` scope only) | 403 | 403 | 403 | 403 |
| `/api/audit` | 2xx | 403 | 403 | 403 | 403 |

The full matrix including patients, appointments, staff, availability, hierarchy, and every other
family is in `docs/api.md`; the asymmetries (BILLING sees invoices but not admissions;
RECEPTIONIST sees admissions but not invoices; network comparison is organization-ADMIN only) are
pinned end-to-end by tests.

## Failure-state summary

- `401` — no valid session or acting context: the frontend returns to Login via the
  session-expiry callback.
- `403` — authenticated role/scope refused: shared permission-denial message; server is
  authoritative.
- `400` — validation failure or malformed body: stable `ApiError` body with a field summary.
- `404` — unknown record, unresolvable reference, or **cross-branch access**: one generic safe
  message naming the type; cross-branch and missing are indistinguishable.
- `409` — illegal lifecycle transition, duplicate natural key (invoice number, bed identity), or
  scheduling conflict: controlled, client-safe conflict messages; nothing is overwritten, no
  partial bed mutation survives, and no persistence internals leak.

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

The smoke performs the same journey per run — acting-context selection → patient → availability →
appointment → required overlap `409` → beds → admit → atomic transfer → discharge with release →
emergency visit through its terminal state → invoice through `PAID` → branch dashboard → network
dashboard → filtered audit with acting context → second-branch isolation proof — and fails
non-zero on any contract deviation, scope leakage, or partial bed mutation. Dated baseline numbers
live in `docs/performance.md` (single-workstation regression tripwire, explicitly not a capacity
claim).

## Boundary statement (restated)

Everything above is a **Training/Portfolio demonstration on synthetic data only, within one
synthetic organization**: no certified medical software, no clinical decision support, no medical
advice, no real triage protocol, no payment processing/tax/FX/collection, no real hospital data,
no SaaS multi-tenancy, and no production, Pilot, or capacity claim of any kind. Moving beyond this
boundary requires an explicit, separate owner decision.
