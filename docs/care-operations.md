# The Demonstrated Care-Operations Journey

> **Training/Portfolio only. Synthetic data only.** MediCore is an educational, non-clinical
> training project — **not** certified medical software. Nothing here is clinical decision
> support, medical advice, or a real triage protocol. The invoice family is a **financial
> simulation**: no payment processing, tax, FX, or collection exists or is implied. No real
> hospital data is used anywhere, and nothing in this document is a production, Pilot, or
> capacity claim.

This document tells the care-operations story the Phase 2 milestone (docs/plan2.md) actually
demonstrates, on top of the Phase 1 patient journey (`docs/patient-journey.md`): a synthetic
patient gets an appointment, is admitted and discharged, walks through an emergency visit with a
neutral demo triage label, and receives a simulated invoice — with every step reference-checked,
lifecycle-validated, role-gated, and audited. Every claim was verified against source and is
pinned by automated tests; `docs/traceability.md` is the evidence map and `docs/api.md` the full
endpoint reference.

## Before you start

Start a disposable local backend with the opt-in synthetic demo cohort
(`MEDICORE_DEMO_SEED=true`) so patient and professional references resolve — exact commands and
required environment variable **names** are in `docs/runbook.md`; values live only in the runtime
environment. Log in as an ADMIN account provisioned via `HOSPITAL_ADMIN_PASSWORD` (ADMIN can
perform every step below; the role boundary table says who else can).

## The journey at a glance

| Step | What happens | Server-owned outcome | Who |
|---|---|---|---|
| Patient + appointment | Phase 1 journey: register, search, schedule | Verified references | ADMIN, RECEPTIONIST (writes) |
| Admit | Register an admission for the existing patient | `status=ADMITTED` | ADMIN, DOCTOR, NURSE, RECEPTIONIST |
| Discharge | Transition the admission | `status=DISCHARGED`, server-stamped `dischargedAt` | same four roles |
| Emergency visit | Register a visit with a neutral `1–5` demo triage label | `status=WAITING` | same four roles |
| Visit transitions | `WAITING → IN_TREATMENT → CLOSED` | Server-validated transitions | same four roles |
| Invoice | Create a uniquely numbered simulated invoice | `status=DRAFT` | ADMIN, BILLING only |
| Invoice lifecycle | `DRAFT → ISSUED → PAID` (or `VOID` at either step) | Server-validated transitions | ADMIN, BILLING only |
| Dashboard | Read the status-aware aggregates | Eleven count keys | any authenticated role |
| Audit | Inspect the evidence trail | One event per successful mutation | ADMIN only |

## Step 1 — Existing synthetic patient and appointment

The journey starts from a patient that already exists: either a `DEMO-0001..0003` record from the
opt-in seed or a record created through the Phase 1 registration flow. The seed also provides the
professional reference (`DEMO-STAFF-001..002`) that appointment scheduling resolves. Registration,
search, detail, edit, and scheduling behavior are documented in `docs/patient-journey.md` and are
not repeated here.

## Step 2 — Admission with server-stamped discharge

- **Who:** `ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST` (server family rule on `/api/admissions/**`;
  the UI offers the Admissions destination to exactly these roles).
- **API:** `POST /api/admissions` with `{patientId, admittedAt, reason}` — the create request
  deliberately carries **no** status field. The service resolves `patientId` against the patient
  repository (unknown → `404`) and sets `status=ADMITTED` itself.
- **Discharge:** `PUT /api/admissions/{id}/status` with `{"status":"DISCHARGED"}` — the only
  legal transition, legal only from `ADMITTED`. The **server stamps `dischargedAt`**; the client
  cannot supply it, and discharging an already-discharged admission is refused with `409`.
- **UI:** the Admissions screen lists admissions with status badges, offers a register form with
  patient selection, and a guarded discharge action; Patient detail offers "Register admission"
  for the preselected patient.

Failure examples: unknown `patientId` → `404`; blank `reason` or malformed body → `400`; repeat
discharge → `409`; `BILLING` calling any admissions route → `403`; no/expired session → `401`.

## Step 3 — Emergency visit with a neutral triage label

- **Who:** the same four roles (server family rule on `/api/emergency-visits/**`).
- **API:** `POST /api/emergency-visits` with `{patientId, arrivalAt, triageLevel, chiefComplaint}`.
  `triageLevel` is a **neutral demo label restricted to `1`–`5`** by a pattern-validated contract.
  It is not ATS, ESI, MTS, or any real triage protocol, carries no assessment or prioritization
  semantics, and never influences any decision. The server sets `status=WAITING`.
- **Transitions:** `PUT /api/emergency-visits/{id}/status` — the only legal paths are
  `WAITING → IN_TREATMENT`, `WAITING → CLOSED`, and `IN_TREATMENT → CLOSED`; `CLOSED` is
  terminal. Anything else is refused with `409`.

Failure examples: triage label `6` or non-numeric → `400`; unknown `patientId` → `404`;
`CLOSED → IN_TREATMENT` → `409`; `BILLING` → `403`.

## Step 4 — Invoice financial simulation

- **Who:** `ADMIN` and `BILLING` **only** — `SecurityConfig` maps `/api/invoices/**` to these two
  roles on every method, so RECEPTIONIST, DOCTOR, and NURSE receive `403` on reads and writes
  alike, and the UI offers the Invoices destination to no one else.
- **API:** `POST /api/invoices` with `{patientId, invoiceNumber, amount, currency}` — again no
  client-supplied status. `amount` is a non-negative demo value with at most 12 integer and 2
  fraction digits, stored canonically (an exponent-shaped `1E+3` is echoed back as plain
  `1000`); `currency` is a three-letter uppercase **demo label** with no conversion or FX
  meaning. The server sets `status=DRAFT`.
- **Uniqueness:** `invoiceNumber` must be unique — a service pre-check returns `409` without
  overwriting the original, and a database unique constraint is the concurrency backstop (a
  race-lost violation maps to a fixed generic conflict message; SQL internals never leak).
- **Lifecycle:** `DRAFT → ISSUED`, `DRAFT → VOID`, `ISSUED → PAID`, `ISSUED → VOID`; `PAID` and
  `VOID` are terminal. Illegal, repeated, or backward transitions are refused with `409`.

The invoice screen states on-page: *financial simulation — no real payments. Amounts and currency
labels are demo values with no conversion, FX, or tax meaning.* No payment gateway, tax,
regulatory, refund, or insurance-claim behavior exists anywhere in the project.

## Step 5 — Status-aware dashboard

- **Who:** any authenticated role (server rule unchanged from Phase 1).
- **API:** `GET /api/dashboard` returns eleven flat count keys: the five legacy totals
  (`patients`, `appointments`, `admissions`, `emergencyVisits`, `invoices`) plus the status-aware
  `openAdmissions` (`ADMITTED`), `activeEmergencyVisits` (`WAITING` + `IN_TREATMENT`), and the
  invoice buckets `invoicesDraft`, `invoicesIssued`, `invoicesPaid`, `invoicesVoid`.
- **UI:** the dashboard groups the keys into labeled sections — Current activity, Totals, and
  Invoices by status — with a generic fallback for unknown keys. With the demo seed enabled the
  buckets are non-zero by construction (`docs/runbook.md` lists the expected values).

## Step 6 — ADMIN audit visibility

Every successful mutation in the journey writes **exactly one** audit event with the acting
username: admission create, discharge, emergency-visit create, every visit transition, invoice
create, every invoice transition, and delete operations. The event `details` payload names the
transition (for example `status: DISCHARGED`). Failed operations — `400` validation, `404`
unknown reference, `409` conflict, `403` refusal — persist nothing and record **no** event. The
ADMIN Audit screen surfaces the new resource types (`Admission`, `EmergencyVisit`, `Invoice`)
next to the Phase 1 events; seeded records record their CREATE events with the `system` actor on
first seed only.

## Role boundaries

The server is the only authority; the frontend permission map is a convenience that mirrors it so
the interface never offers what the backend refuses.

| Family | ADMIN | DOCTOR | NURSE | RECEPTIONIST | BILLING |
|---|---|---|---|---|---|
| `/api/admissions/**` | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/emergency-visits/**` | 2xx | 2xx | 2xx | 2xx | 403 |
| `/api/invoices/**` | 2xx | 403 | 403 | 403 | 2xx |
| `/api/dashboard/**` | 2xx | 2xx | 2xx | 2xx | 2xx |
| `/api/audit` | 2xx | 403 | 403 | 403 | 403 |

The full matrix including patients, appointments, staff, and every other family is in
`docs/api.md`; the asymmetries (BILLING sees invoices but not admissions; RECEPTIONIST sees
admissions but not invoices) are pinned end-to-end by tests.

## Failure-state summary

- `401` — no valid session: the frontend returns to Login via the session-expiry callback.
- `403` — authenticated role refused: shared permission-denial message; server is authoritative.
- `400` — validation failure or malformed body: stable `ApiError` body with a field summary.
- `404` — unknown record or unresolvable patient reference: safe `ApiError` naming the type.
- `409` — illegal lifecycle transition or duplicate natural key (invoice number): controlled,
  client-safe conflict messages; nothing is overwritten and no persistence internals leak.

## How to verify this journey yourself

```bash
# automated suites
cd backend && mvn test                        # 70 tests
cd ../frontend && npm test && npm run build   # 125 tests + production build

# repeatable API smoke of the full care-operations journey (a disposable local
# backend must already be running with the demo seed enabled — see docs/runbook.md)
BASE_URL=http://127.0.0.1:5501 RUNS=3 \
HOSPITAL_SMOKE_PASSWORD="<disposable local value>" \
./scripts/smoke-patient-journey.sh
```

The smoke performs the same journey per run — patient → appointment → admission with discharge →
emergency visit through its terminal state → invoice through `PAID` → eleven-key dashboard — and
fails non-zero on any contract deviation. Dated baseline numbers live in `docs/performance.md`
(single-workstation regression evidence, explicitly not a capacity claim).

## Boundary statement (restated)

Everything above is a **Training/Portfolio demonstration on synthetic data only**: no certified
medical software, no clinical decision support, no medical advice, no real triage protocol, no
payment processing/tax/FX/collection, no real hospital data, and no production, Pilot, or
capacity claim of any kind. Moving beyond this boundary requires an explicit, separate owner
decision.
