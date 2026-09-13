# Performance Evidence (Plan 2 Task 9 — care-operations journey smoke)

Scope: **Training/Portfolio only.** This document records a repeatable local
smoke for the demonstrated care-operations journey (`docs/plan2.md` Task 9,
which extends the `docs/plan1.md` Task 12 patient journey). It makes **no
production-capacity claim and defines no SLO**: numbers describe one developer
workstation, an in-memory database, and a single-digit-row dataset. They exist
so a future regression in the demonstrated workflow becomes visible when the
same script is re-run on comparable hardware.

## Supersession of the Task 12 numbers

The 2026-09-09 baseline recorded at the bottom of this file is **superseded
and must not be used as a regression reference**: both the measured journey
and the seeded dataset changed since it was taken. The script now executes
the full care-operations lifecycle per run (patient → appointment → admission
with discharge → emergency visit through its terminal state → invoice through
`PAID` → eleven-key dashboard), roughly doubling the request count of the old
seven-step journey, and the Task 8 fixtures add status-aware rows that the old
dataset did not contain. Old numbers cannot be compared to new ones.

The 2026-09-13 Task 14 baseline recorded below **likewise supersedes the
Task 9 baseline in this file**: the measured journey changed again (branch-aware
care operations — context selection onto an organization-ADMIN assignment, a
second-branch isolation proof, a typed branch dashboard plus an organization
network dashboard, and audit-context evidence were added; the appointment step
now books the earliest slot that overlaps no appointment already on the books),
and the seeded dataset now spans three active branches with dated 2031
availability fixtures. Old numbers cannot be compared to Task 14 numbers; the
Task 9 section below is retained as history, not as a regression reference.

## The measured workflow (per run, each step timed in ms)

> Task 9 description, kept for history. The script now executes the Task 14
> branch-aware journey; the current per-run workflow is described in the
> Task 14 baseline section below.

1. `login` — POST `/api/auth/login`; contract `{accessToken, roles[]}` with an
   ADMIN/RECEPTIONIST-capable role.
2. `patient_create` — POST `/api/patients` with a distinct synthetic patient
   (MRN `SMOKE-<timestamp>-<run>`).
3. `patient_search` — GET `/api/patients?q=<name>`; the record must come back.
4. `patient_detail` — GET `/api/patients/{id}`; the id must echo.
5. `appointment_create` / `appointment_list` — POST `/api/appointments`
   against a seeded professional, then the list must contain the appointment.
6. `admission_create` — POST `/api/admissions`; the server sets
   `status=ADMITTED`.
7. `admissions_DISCHARGED` — PUT `/api/admissions/{id}/status`; the server
   applies the sole `ADMITTED → DISCHARGED` transition and stamps a nonblank
   `dischargedAt`, which the script requires.
8. `emergency_create` — POST `/api/emergency-visits`; the server sets
   `status=WAITING`. The triage label is a neutral 1–5 demo value with no
   clinical meaning.
9. `emergency-visits_IN_TREATMENT` → `emergency-visits_CLOSED` — the only
   legal `WAITING → IN_TREATMENT → CLOSED` path; each returned status is
   asserted.
10. `invoice_create` — POST `/api/invoices` with a per-run unique number
    `SMOKE-INV-<timestamp>-<run>` and display-only demo amount/currency
    (**financial simulation only**: no payments, gateways, taxes, or real
    money); the server sets `status=DRAFT`.
11. `invoices_ISSUED` → `invoices_PAID` — the legal `DRAFT → ISSUED → PAID`
    path; each returned status is asserted.
12. `dashboard` — GET `/api/dashboard`; all eleven numeric count keys are
    required (`patients`, `appointments`, `admissions`, `emergencyVisits`,
    `invoices`, `openAdmissions`, `activeEmergencyVisits`, `invoicesDraft`,
    `invoicesIssued`, `invoicesPaid`, `invoicesVoid`). The five whole-table
    totals must at least be consistent with the journey just executed (each
    ≥ 1, without assuming an empty database), and every status-aware key must
    remain numeric and non-negative. Zero is **not** required anywhere: the
    Task 8 seed deliberately contains open/active/draft/issued/void fixtures
    alongside the run's own discharged/closed/paid records.

## How to reproduce

1. Start a **disposable, isolated** local backend with an in-memory database
   and the synthetic demo cohort (professional references must resolve — see
   docs/runbook.md):

   ```bash
   cd backend
   MEDICORE_DEMO_SEED=true \
   HOSPITAL_ADMIN_PASSWORD="<disposable local value, >=12 chars>" \
   HOSPITAL_JWT_SECRET="<disposable local value>" \
   SPRING_DATASOURCE_URL='jdbc:h2:mem:smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1' \
   SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop \
   mvn spring-boot:run            # add -Dspring-boot.run.arguments=--server.port=<port> if 5501 is busy
   ```

2. Run the smoke (password = the same disposable value supplied above; the
   script reads it from `HOSPITAL_SMOKE_PASSWORD` and never hardcodes or
   echoes it):

   ```bash
   BASE_URL=http://127.0.0.1:5501 RUNS=3 \
   HOSPITAL_SMOKE_PASSWORD="<same disposable value>" \
   ./scripts/smoke-patient-journey.sh
   ```

The script boots nothing itself and cleans nothing by design: it may only
target a disposable ephemeral/local demo database (e.g. in-memory H2), never
shared or live data. It fails on any non-2xx response, any missing or
malformed JSON contract field (`accessToken`/`roles` at login, `id` on created
records, the server-returned lifecycle status on create/transition, response
arrays, the typed branch/network dashboard count keys, the acting-context
fields), a missing id/reference, or an unexpected lifecycle status, and exits
non-zero. Error messages never include
response bodies (they can carry tokens). Every record it creates is synthetic
(`SMOKE-<timestamp>-<run>` MRNs, `SMOKE-INV-…` invoice numbers,
`synthetic.example.test` emails).

## Environment and dataset assumptions (recorded separately from results)

- Workstation: shared Linux development sandbox; backend on Temurin 21,
  Spring Boot embedded Tomcat bound to loopback (`BASE_URL`, default
  `http://127.0.0.1:5501`).
- Database: isolated in-memory H2 (`MODE=PostgreSQL`), `create-drop`; demo
  cohort seeded via `MEDICORE_DEMO_SEED=true`.
- Seeded dataset (Task 8 fixture composition) before any smoke run:
  3 patients, 2 professionals, 2 appointments, 2 admissions
  (1 `ADMITTED` + 1 `DISCHARGED`), 3 emergency visits (1 each `WAITING`,
  `IN_TREATMENT`, `CLOSED`), and 4 invoices (1 each `DRAFT`, `ISSUED`,
  `PAID`, `VOID`). Each smoke run adds its own synthetic records on top.
- Build: the exact stacked revision under measurement, launched via
  `mvn spring-boot:run` (no stale artifact).
- Timing method: wall-clock ms around each HTTP call from bash
  (`date +%s%3N`), including curl process startup and JWT parse — this
  measures the whole client-observed step, not server-internal query time.
- JVM and JIT are cold at the first run; single-digit samples are expected to
  show warmup outliers (see the max column of any recorded run).

## Budget definition (Training/Portfolio scope)

Before enforcing any numeric threshold, the budget is defined **relative to a
reviewed baseline** so it transfers across machines:

> **p95 step budget: any step's p95 across `RUNS` must stay below 3× that
> step's baseline median recorded on the same machine and dataset size.**

This is a regression tripwire for the demonstrated workflow only — not an SLO,
not a capacity statement, and not comparable across different hardware or data
volumes. The reference medians are defined by the **measured Task 14 baseline
below**; the superseded Task 9 and Task 12 tables in this file are history,
not a reference.

## Measured Task 9 baseline (isolated live run) — superseded 2026-09-13, history only

> Superseded by the Task 14 baseline below: different journey, different
> dataset. Retained as recorded evidence; not a regression reference.

The baseline below is observed evidence from one isolated live measurement,
recorded by the supervisor against a backend booted solely for that run:

- Backend/application source revision: commit
  `e7771365fd9332f698644a5d8c7b8900ed5ab76b` on
  `test/care-operations-smoke-performance` (PRs #22 → #21 → #20 → #19).
  The expanded care-operations script that produced these numbers was the
  Task 9 working diff in the same tree — uncommitted at measurement time and
  committed together with this document — so an uncommitted diff is not
  called a commit here; only the backend/application source is identified by
  a commit id.
- Isolated runtime: loopback-only bind `127.0.0.1:5592`, in-memory H2,
  `create-drop`, and the Task 8 synthetic demo seed enabled
  (`MEDICORE_DEMO_SEED=true`). No managed review service was touched. After
  evidence capture the disposable runtime was stopped, the port was closed,
  and the runtime-generated credential temp file was removed.
- Run: `RUNS=5`, script exit 0,
  `SMOKE RESULT: PASS (runs=5, total_ms=18748)`. The table values are the
  script's own timing summary, copied verbatim from the recorded run output;
  no value is derived, recalculated, or invented.

| step                           | min (ms) | median (ms) | max (ms) |
|--------------------------------|---------:|------------:|---------:|
| login                          |      151 |         161 |      166 |
| patient_create                 |       34 |          41 |       44 |
| patient_search                 |       33 |          35 |       44 |
| patient_detail                 |       26 |          28 |       40 |
| appointment_create             |       33 |          42 |       47 |
| appointment_list               |       28 |          29 |       58 |
| admission_create               |       34 |          35 |       46 |
| admissions_DISCHARGED          |       35 |          38 |       42 |
| emergency_create               |       38 |          42 |       46 |
| emergency-visits_IN_TREATMENT  |       31 |          39 |       46 |
| emergency-visits_CLOSED        |       32 |          37 |       39 |
| invoice_create                 |       36 |          43 |       57 |
| invoices_ISSUED                |       38 |          43 |       46 |
| invoices_PAID                  |       31 |          41 |      667 |
| dashboard                      |       53 |          58 |       73 |

Interpretation boundary (unchanged): these numbers prove repeatable
functional success and a small local timing baseline only. They are not load,
concurrency, soak, capacity, production-readiness, SLA, or SLO evidence. The
single `invoices_PAID` maximum of 667 ms is retained exactly as observed and
counts as an observed outlier only — no cause is guessed and no optimization
claim is made from five sequential runs.

## Measured Task 14 baseline (isolated live run, 2026-09-13) — current reference

The baseline below is observed evidence from one isolated live measurement of
the Task 14 branch-aware care-operations journey (`docs/plan3.md` Task 14),
recorded against a disposable backend booted solely for that run:

- Backend/application source revision: commit
  `620d107602e197af6d0550eb8135a10a532e99eb`. The expanded branch-aware script
  that produced these numbers is the Task 14 working diff in the same tree —
  uncommitted at measurement time, exactly like the Task 9 record above — so
  only the backend/application source is identified by a commit id and the
  uncommitted script diff is not called a commit.
- Journey per run (36 timed steps): login → context select onto the
  organization-ADMIN assignment and the primary branch → synthetic patient
  create/search/detail → staff availability + branch appointment list read →
  appointment create at the earliest slot overlapping no appointment already
  on the books → dedicated overlapping-appointment `409` probe → two beds →
  admission taking bed A (server sets `ADMITTED`, occupies A) → atomic
  transfer to bed B with both occupancies verified (any partial bed mutation
  fails the run) → discharge (server-stamped `dischargedAt`, B released) →
  emergency visit through its only legal `WAITING → IN_TREATMENT → CLOSED`
  path → simulated invoice through `DRAFT → ISSUED → PAID` (**financial
  simulation only**: display-only demo amount/currency; no payments,
  gateways, taxes, or real money) → branch dashboard (typed summary must
  match the acting branch) → organization network dashboard (every total must
  equal the exact sum of the returned per-branch summaries) → filtered audit
  evidence carrying the full acting context (assignment, role, scope,
  organization, branch, actor) → context switch to the second active branch →
  nine isolation reads proving every primary-branch row is absent there
  (search miss, 404 detail reads, list matches of zero, no audit row) →
  second branch dashboard.
- Isolated runtime: loopback-only bind `127.0.0.1:5593`, in-memory H2
  (`MODE=PostgreSQL`, `DB_CLOSE_DELAY=-1`, `create-drop`), synthetic demo seed
  enabled (`MEDICORE_DEMO_SEED=true`; three active branches
  `DEMO-BR-001/002/003`, each with its own patients, professionals, dated
  2031 availability fixtures, appointments, beds, admissions, emergency
  visits, and invoices). Login credentials were random values generated
  inside the runtime's own child shell (`HOSPITAL_ADMIN_PASSWORD`,
  `HOSPITAL_JWT_SECRET`, `HOSPITAL_SMOKE_PASSWORD`) and were never printed,
  persisted, or committed; backend readiness was proven through the login
  endpoint itself before measurement started. No managed review service was
  touched. After evidence capture the disposable runtime was stopped, the
  port was closed, and all runtime temp files were removed.
- Run: `RUNS=5`, script exit 0,
  `SMOKE RESULT: PASS (runs=5, total_ms=56627)`. The table values are the
  script's own timing summary, copied verbatim from the recorded run output;
  no value is derived, recalculated, or invented.

| step                           | min (ms) | median (ms) | max (ms) |
|--------------------------------|---------:|------------:|---------:|
| login                          |      185 |         195 |      253 |
| context_select                 |       39 |          46 |       97 |
| patient_create                 |       28 |          40 |      119 |
| patient_search                 |       28 |          36 |       60 |
| patient_detail                 |       32 |          35 |       54 |
| staff_availability             |       62 |          74 |      171 |
| appointment_create             |       58 |          74 |      122 |
| appointment_conflict_409       |       35 |          46 |       72 |
| bed_create_a                   |       32 |          41 |       70 |
| bed_create_b                   |       28 |          36 |      147 |
| admission_create               |       42 |          43 |      316 |
| bed_occupy_verify              |       23 |          41 |       48 |
| admission_bed_transfer         |       32 |          44 |       62 |
| bed_transfer_release_verify    |       33 |          45 |       50 |
| bed_transfer_occupy_verify     |       26 |          30 |       49 |
| admissions_DISCHARGED          |       40 |          44 |      110 |
| bed_discharge_release_verify   |       34 |          35 |       44 |
| emergency_create               |       31 |          46 |       60 |
| emergency-visits_IN_TREATMENT  |       43 |          46 |       51 |
| emergency-visits_CLOSED        |       32 |          36 |       53 |
| invoice_create                 |       31 |          31 |       90 |
| invoices_ISSUED                |       25 |          34 |       55 |
| invoices_PAID                  |       31 |          41 |       62 |
| branch_dashboard               |       36 |          55 |      101 |
| network_dashboard              |       39 |          45 |       87 |
| audit_context                  |       36 |          50 |       56 |
| context_switch                 |       45 |          49 |       62 |
| isolation_patient_search       |       30 |          34 |       59 |
| isolation_patient_detail       |       30 |          37 |       41 |
| isolation_bed                  |       30 |          31 |       41 |
| isolation_admission            |       27 |          37 |       44 |
| isolation_appointments         |       26 |          38 |       43 |
| isolation_emergency            |       28 |          39 |      339 |
| isolation_invoices             |       29 |          37 |       40 |
| isolation_audit                |       37 |          41 |       67 |
| branch_dashboard_other         |       36 |          54 |       58 |

Interpretation boundary (unchanged): these numbers prove repeatable
functional success and a small local timing baseline only. They are not load,
concurrency, soak, capacity, production-readiness, SLA, or SLO evidence.
JVM and JIT are cold at the first run, so single-digit samples show warmup
outliers (e.g. the `admission_create` maximum of 316 ms and the
`isolation_emergency` maximum of 339 ms are retained exactly as observed and
count as observed outliers only — no cause is guessed and no optimization
claim is made from five sequential runs). The `staff_availability` step now
covers two HTTP reads (availability plus the branch appointment list used for
conflict-free slot selection), so it is not directly comparable to the Task 9
step of the same name.

## Observed failure modes (RED evidence)

Each of these exits non-zero with a clear message and creates no data:

- Backend not reachable: `preflight /actuator/health returned HTTP 000 —
  backend not reachable at http://127.0.0.1:5501` → exit 1.
- Missing `HOSPITAL_SMOKE_PASSWORD`: refused before any request → exit 1.
- Wrong credentials: `preflight login returned HTTP 401` → exit 1.
- No resolvable professional (backend started without `MEDICORE_DEMO_SEED=true`
  and an empty staff table): the script **fails by design** with a message
  pointing at the seed flag — the demonstrated workflow is the point, so it
  never silently degrades to a skip-create mode.
- Unexpected lifecycle status: any create/transition response whose
  server-returned `status` differs from the expected state fails the run
  immediately (verified against a deliberately contract-breaking stub:
  `admission create returned status 'WAITING' (expected 'ADMITTED')` → exit 1).

## Dashboard count queries — implementation note and standing decision

`DashboardService.summary()` (read-only inspection, unchanged security rule:
any authenticated role) issues twelve repository count calls per request — five
whole-table `count()` totals plus seven status-bucket counts (`countByStatus`
for admissions once, emergency visits twice — `activeEmergencyVisits` sums the
`WAITING` and `IN_TREATMENT` buckets — and invoices once per
`DRAFT`/`ISSUED`/`PAID`/`VOID`), producing the eleven contract keys in a fixed
insertion order.

Decision: **no optimization is justified without new evidence.** Earlier
recorded measurements predate the current dashboard surface and are
superseded; the measured Task 14 baseline above is the first valid measurement
of that surface (typed branch summary plus organization network summary) in
its current shape. A single-aggregate rewrite would change a verified, frozen
contract surface for an unmeasured benefit. Re-open this only if the measured
Task 14 baseline above — or a later one — shows either dashboard step's p95
exceeding 3× its baseline median while other read steps stay within budget;
that pattern, not intuition, would identify the count queries as the
bottleneck. If `DashboardController` or
`DashboardService` ever changes for this reason, `DashboardControllerTest`
must be extended in the same packet.

---

## Superseded historical baseline — Task 12, 2026-09-09 (reference only)

The tables below are the old seven-step patient-journey numbers. They were
taken against a smaller journey, a smaller dataset, and an older dashboard
contract; they are **not** a valid regression reference for the Task 9 script.

Official baseline run (`RUNS=3`):

| step               | min (ms) | median (ms) | max (ms) |
|--------------------|---------:|------------:|---------:|
| login              |      189 |         191 |      200 |
| patient_create     |       38 |          50 |      215 |
| patient_search     |       36 |          37 |      219 |
| patient_detail     |       35 |          39 |       69 |
| appointment_create |       33 |          42 |      101 |
| appointment_list   |       28 |          32 |       34 |
| dashboard          |       45 |          56 |      565 |

`SMOKE RESULT: PASS (runs=3, total_ms=5240)` — script exit 0.

Stability confirmation run, same boot, `RUNS=5`:

| step               | min (ms) | median (ms) | max (ms) |
|--------------------|---------:|------------:|---------:|
| login              |      191 |         194 |      308 |
| patient_create     |       25 |          42 |       47 |
| patient_search     |       27 |          40 |      175 |
| patient_detail     |       29 |          33 |      110 |
| appointment_create |       35 |          47 |       94 |
| appointment_list   |       28 |          30 |      142 |
| dashboard          |       33 |          38 |       76 |

`SMOKE RESULT: PASS (runs=5, total_ms=7709)` — script exit 0.

The `login` median (~190 ms) reflected PBKDF-scale password verification plus
JWT issue on a cold JVM; read steps settled around 30–55 ms including full
HTTP round-trip and client-side JSON parsing. Warmup outliers disappeared in
warmed runs.
