# Performance Evidence (Task 12)

Scope: **Training/Portfolio only.** This document records a repeatable local
smoke for the demonstrated patient journey (`docs/plan1.md` Task 12). It makes
**no production-capacity claim**: numbers below describe one developer
workstation, an in-memory database, and a single-digit-row dataset. They exist
so a future regression in the demonstrated workflow becomes visible when the
same script is re-run on comparable hardware.

## How to reproduce

1. Start a local backend with a disposable in-memory database and the synthetic
   demo cohort (professional references must resolve — see docs/runbook.md):

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
   script reads it from `HOSPITAL_SMOKE_PASSWORD` and never hardcodes it):

   ```bash
   BASE_URL=http://127.0.0.1:5501 RUNS=3 \
   HOSPITAL_SMOKE_PASSWORD="<same disposable value>" \
   ./scripts/smoke-patient-journey.sh
   ```

The script boots nothing itself, fails on any non-2xx response, missing JSON
contract field (`accessToken`/`roles` at login, `id` on created records,
response arrays, dashboard count keys), or an unresolvable workflow reference,
and exits non-zero. It cleans up no data by design: every record it creates is
synthetic (`SMOKE-<timestamp>-<run>` MRNs, `synthetic.example.test` emails) and
must only ever target a disposable local database.

## Environment assumptions (recorded separately from results)

- Workstation: shared Linux development sandbox; backend on Temurin 21,
  Spring Boot embedded Tomcat bound to loopback `127.0.0.1:5591` (port 5501
  was occupied by a separate long-running review instance, so `BASE_URL`
  pointed at 5591).
- Database: isolated in-memory H2 (`MODE=PostgreSQL`), `create-drop`; demo
  cohort seeded via `MEDICORE_DEMO_SEED=true` (3 patients, 2 professionals,
  2 appointments at boot; plus each smoke run's own synthetic records).
- Build: current worktree at commit `016b56a`, launched via
  `mvn spring-boot:run` (no stale artifact).
- Timing method: wall-clock ms around each HTTP call from bash
  (`date +%s%3N`), including curl process startup and JWT parse — this
  measures the whole client-observed step, not server-internal query time.
- JVM and JIT are cold at the first run; single-digit samples are expected to
  show warmup outliers (they do — see max column).

## Budget definition (Training/Portfolio scope)

Before enforcing any numeric threshold, the budget is defined **relative to a
reviewed baseline** so it transfers across machines:

> **p95 step budget: any step's p95 across `RUNS` must stay below 3× that
> step's baseline median recorded on the same machine and dataset size.**

This is a regression tripwire for the demonstrated workflow only — not an SLO,
not a capacity statement, and not comparable across different hardware or data
volumes. The baseline medians below are the reference until the environment
changes materially.

## Baseline results — 2026-09-09

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

The `login` median (~190 ms) reflects PBKDF-scale password verification plus
JWT issue on a cold JVM; read steps settle around 30–55 ms including full HTTP
round-trip and client-side JSON parsing. Warmup outliers (max 565 ms on the
first dashboard call of the first run) disappear in warmed runs.

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

## Dashboard count queries — profiling note and decision

`DashboardController.summary()` (read-only inspection) issues five separate
`Repository.count()` calls — five `select count(*)` queries per request (one
each over patients, appointments, admissions, emergency visits, invoices).

Decision: **no optimization is justified by the evidence.** In both recorded
runs the dashboard step's median (56 ms / 38 ms) is within the same band as
every other authenticated read step (30–55 ms), and its warmed maximum (76 ms)
is unremarkable. Client-observed latency is dominated by HTTP round-trip and
cold-JVM warmup, not by the count queries, which execute over single-digit-row
tables where `select count(*)` costs microseconds. A single-aggregate rewrite
(`select count(*) ... union all` or a JPQL constructor projection) would not
move any measured number and would change a verified, frozen contract surface
for no measured benefit.

Re-open this only if a future smoke run shows the dashboard step's p95
exceeding 3× its baseline median while other read steps stay within budget —
that pattern, not intuition, would identify the count queries as the
bottleneck. If `DashboardController` ever changes for this reason,
`DashboardControllerTest` must be extended in the same packet.
