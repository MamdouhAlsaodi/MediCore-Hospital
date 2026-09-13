# Local Review Runbook

MediCore is an educational, non-clinical training project, not certified clinical software. Do not use it for patient care or real clinical decisions.

## Runtime values
Provide `HOSPITAL_ADMIN_PASSWORD` and `HOSPITAL_JWT_SECRET` only through the runtime environment; do not place secrets in tracked files. `HOSPITAL_ADMIN_PASSWORD` is required (minimum 12 characters) while the `admin` account does not exist yet; startup fails with a clear configuration error if it is missing, blank, or too short, and an existing `admin` account is never modified. Optional PostgreSQL values are `DB_URL`, `DB_USER`, and `DB_PASSWORD`. `.env.example` lists the variable names; values live outside the repository.

## Authentication and authorization
The login response returns `accessToken`, `tokenType`, `username`, and the account's role names; it never returns a password hash or internal entity. The frontend stores the session in `sessionStorage` only (cleared when the tab closes) and purges the legacy `localStorage.token` key on boot. Unauthenticated visitors see only the dedicated Login page. Endpoint families carry explicit role rules in `SecurityConfig` — including method-level write rules for patient create/update and appointment create — ordered before an ADMIN-only catch-all for unmatched `/api/**`. Unauthenticated requests receive `401`; an authenticated role without permission receives `403`. The enforced role matrix is documented in `docs/api.md`.

## Backend
Requirements: Java 21 and Maven 3.9+.

```bash
cd backend
HOSPITAL_ADMIN_PASSWORD="$HOSPITAL_ADMIN_PASSWORD" HOSPITAL_JWT_SECRET="$HOSPITAL_JWT_SECRET" mvn spring-boot:run
```

The review backend listens on loopback port `5501`. H2 file data is local-only under `backend/data/`. The H2 console is disabled and must never be exposed or re-enabled for public review. Authorization integration tests (`SecurityAuthorizationTest`) run against an isolated in-memory H2 database and never touch the production H2 files.

## Frontend

Development (live reload):

```bash
cd frontend
REVIEW_BIND_HOST=<operator-supplied-tailnet-interface-address> npm run dev
```

Serving the already-built bundle for review:

```bash
cd frontend
npm run build
REVIEW_BIND_HOST=<operator-supplied-tailnet-interface-address> npm run preview
```

Both the Vite dev server and the preview server bind only to the narrow interface address supplied through `REVIEW_BIND_HOST` (default `127.0.0.1`), listen on port `5502` with `strictPort` enabled, and proxy `/api` to the loopback backend on port `5501`. Do not replace this with a broad host bind.

## Synthetic demo data (opt-in, disabled by default)

Demo seeding is off unless explicitly enabled. To seed a small synthetic demo cohort into a local review backend, set `MEDICORE_DEMO_SEED=true` in the runtime environment before starting it:

```bash
cd backend
MEDICORE_DEMO_SEED=true HOSPITAL_ADMIN_PASSWORD="$HOSPITAL_ADMIN_PASSWORD" HOSPITAL_JWT_SECRET="$HOSPITAL_JWT_SECRET" mvn spring-boot:run
```

What gets created (all values are obviously synthetic, on the `synthetic.test` demo domain, with `Demo`/`DEMO` names throughout; no real personal, clinical, or financial data; Training/Portfolio use only):

- 1 synthetic organization: `Demo Synthetic Hospital` (`DEMO-ORG-001`)
- 3 unmistakably synthetic branches of varied size, all active: `Demo Main Branch` (`DEMO-BR-001`, the deterministic default), `Demo North Branch` (`DEMO-BR-002`), `Demo Harbor Branch` (`DEMO-BR-003`)
- 4 branch-owned departments: `DEMO-DEP-0001`/`DEMO-DEP-0002` on the main branch, `DEMO-DEP-0101` on north, `DEMO-DEP-0201` on harbor
- 6 branch-owned patients: `Demo Patient Alpha`–`Demo Patient Foxtrot` (`DEMO-0001`–`DEMO-0006`; 3 main, 2 north, 1 harbor), emails on `@synthetic.test`
- 5 branch-owned professionals: `DEMO-STAFF-001`/`DEMO-STAFF-002` (main), `DEMO-STAFF-0101`/`DEMO-STAFF-0102` (north), `DEMO-STAFF-0201` (harbor), each naming a same-branch department
- 5 dated half-open availability intervals (2031 dates) for those professionals, and 4 appointments — every appointment window sits inside its own professional's same-branch availability
- 8 branch-owned beds covering all four operational statuses: main 4 (1 `AVAILABLE`, 1 `OCCUPIED`, 1 `MAINTENANCE`, 1 `OUT_OF_SERVICE`), north 2 (1+1), harbor 2 (`AVAILABLE`), keyed per (branch, ward, room, bedNumber)
- 4 branch-owned admissions: Alpha (main) and Delta (north) `ADMITTED` and each genuinely occupying a bed through a live assignment row; Bravo (main) and Foxtrot (harbor) `DISCHARGED` with no bed
- 5 branch-owned emergency visits: 2 `WAITING` (Charlie main, Echo north), 1 `IN_TREATMENT` (Alpha main), 2 `CLOSED` (Bravo main, Foxtrot harbor), with generic demo complaint labels and the meaningless demo triage labels `1`–`5` (triage here is never a clinical assessment)
- 7 simulated invoices (`DEMO-INV-0101`–`0104` main, `0201`/`0202` north, `0301` harbor): 2 `DRAFT`, 2 `ISSUED`, 2 `PAID`, 1 `VOID` — amounts and currency labels are display-only financial simulation data with no payment semantics

Expected review evidence after startup: log in as the organization `admin` and open the network command center (`/api/dashboard/network`) — it compares all three branch summaries in code order, totals equal the per-branch sums, and every status bucket is populated (4/2/1/1 beds, 2/2/2/1 invoices). A branch-scoped ADMIN context sees the same shape for one branch through `/api/dashboard/branch`; the default branch holds nonzero fixtures in every status bucket. The demo fixtures are dated 2031, so today's-appointment counts honestly stay zero.

Seeding is idempotent and safe to restart: every insert is lookup-before-create against a stable business key (organization code; (organization, code) branch pair; (branch, code) department pair; bed (branch, ward, room, bedNumber); patient MRN; professional employee code; appointment patient+professional+time+type; availability branch+professional+window; admission patient+time+reason; emergency patient+arrival+complaint; unique invoice number), so restarting never duplicates rows, and it never deletes or modifies existing records. The bed-assignment action is idempotent through the live assignment row, so an interrupted startup self-heals instead of leaving a bed/admission contradiction. Unknown pre-existing rows (for example null-branch departments from older data) are never mass-updated, reassigned, or adopted. Every newly created record is recorded as an audit event attributed to the `system` actor (no user is authenticated at startup), carrying the owning branch as its only acting-context value and no correlation id; reused records add no events, which keeps restarts idempotent in the audit trail too. A full first run records exactly 54 events: 52 CREATE events (one per created row) plus the 2 admission bed-assignment actions recorded as `UPDATE Admission` with a `bed: <id>` detail, matching `AdmissionService`. A branch-scoped ADMIN sees its branch's seeding events; an organization-scoped ADMIN sees the full run through the `legacy/unassigned` slice (no acting assignment exists at startup). No accounts or credentials are created.

Verify by logging in as an ADMIN and checking Patients (search `DEMO-`), Appointments, Admissions, Emergency visits, Invoices, the branch/network command centers above, and the Audit screen (events with actor `system`, one per newly created seeded record plus the 2 bed-assignment actions — 54 in total for the full first-run cohort: 1 organization + 3 branches + 4 departments + 6 patients + 5 professionals + 4 appointments + 5 availability intervals + 8 beds + 4 admissions + 5 emergency visits + 7 invoices, plus 2 assignment actions). Never enable demo seeding against a shared or production data store.

## Review accounts (opt-in, disabled by default)

Review-account bootstrapping is off unless explicitly enabled. For a local training/review backend that needs one DOCTOR and one NURSE reviewer login, set `MEDICORE_REVIEW_ACCOUNTS_ENABLED=true` in the runtime environment and provide both review passwords before starting it:

```bash
cd backend
MEDICORE_REVIEW_ACCOUNTS_ENABLED=true \
HOSPITAL_REVIEW_DOCTOR_PASSWORD="$HOSPITAL_REVIEW_DOCTOR_PASSWORD" \
HOSPITAL_REVIEW_NURSE_PASSWORD="$HOSPITAL_REVIEW_NURSE_PASSWORD" \
HOSPITAL_ADMIN_PASSWORD="$HOSPITAL_ADMIN_PASSWORD" HOSPITAL_JWT_SECRET="$HOSPITAL_JWT_SECRET" \
mvn spring-boot:run
```

Behavior and boundaries:

- When the flag is enabled, both variables are required and must each be at least 12 characters. Startup fails with a configuration error naming the offending variable (never any value) if one is missing, blank, or too short.
- Startup creates username `doctor` with exactly the `DOCTOR` role and username `nurse` with exactly the `NURSE` role, encoded with the same BCrypt encoder as every other account. Both creations are lookup-before-create: an existing account is never duplicated, and its password, roles, or any other field are never modified, so restarts are idempotent.
- With the flag false or absent, behavior is exactly as before: only the initial `admin` bootstrap applies, no `doctor`/`nurse` accounts are created, and the review-password variables are never required.
- These accounts exist only to exercise DOCTOR/NURSE views during training and review. MediCore is an educational, non-clinical project: never enable this against a shared or production data store, and treat the review credentials as disposable values owned entirely by the runtime environment (never tracked files).

## Automated verification

Run the full test gates from the repository root:

```bash
cd backend && mvn test                        # 162 tests (MultiBranchOperationsApiTest 43,
                                              # SecurityAuthorizationTest 38,
                                              # PatientJourneyApiTest 24,
                                              # CareOperationsApiTest 23,
                                              # DemoDataInitializerTest 13,
                                              # DevAdminInitializerTest 13,
                                              # DashboardApiTest 7,
                                              # ArchitectureSmokeTest 1)
cd ../frontend && npm test && npm run build   # 237 tests across 15 files + production build
cd ../frontend && npm run test:e2e            # real-browser journey; see "Browser evidence" above
cd .. && git diff --check                     # whitespace/conflict-marker gate
```

## Browser evidence (Playwright e2e)

The Task 13 browser journey proves the multi-branch workflow in a real Chromium browser at two viewports (desktop 1280×720, mobile 375×812) against the disposable loopback review pair. It is part of the Phase 3 acceptance gate, not a manual step:

```bash
cd frontend
npm test && npm run build && npm run test:e2e
```

Prerequisites (in addition to the Java 21 + Maven backend toolchain and Node):

```bash
cd frontend
npm install                 # installs the pinned @playwright/test 1.61.0
npx playwright install chromium   # one-time local browser download
```

Environment (names only — supply disposable values at invocation, never in tracked files):

- `HOSPITAL_ADMIN_PASSWORD` — required; the synthetic `admin` login used by the journey.
- `HOSPITAL_JWT_SECRET` — required; the backend refuses to boot without it.
- If either variable is missing, the runner fails before anything starts; no other configuration is read.

Dedicated review ports (collision-free by construction):

- The runner binds the backend to `http://127.0.0.1:5591` and the frontend to `http://127.0.0.1:5592` — dedicated loopback review ports that no other MediCore workflow uses. The ports are passed to both servers explicitly (`SERVER_PORT` for Spring Boot; `REVIEW_FRONTEND_PORT` and `REVIEW_API_PROXY_TARGET` for Vite), so nothing falls back to the shared development ports.
- `reuseExistingServer` is unconditionally `false` for both servers: the runner never attaches to an already-running service and never reuses one of its own previous instances. If either dedicated port is occupied by a foreign process, the run fails instead of reaching the wrong instance. Existing services on any other port are left untouched.

Startup order and endpoints (both servers are managed by `frontend/playwright.config.js`; nothing else needs to be started):

1. The config wipes the disposable H2 store under `/tmp/medicore-e2e-h2` (outside the repository), then starts the Spring Boot backend on `http://127.0.0.1:5591` (explicit `SERVER_PORT`) with `MEDICORE_DEMO_SEED=true` and `SPRING_DATASOURCE_URL` pointed at that store, waiting for `/actuator/health`.
2. It then starts the Vite dev server on `http://127.0.0.1:5592` (explicit `REVIEW_FRONTEND_PORT`, proxying `/api` to `http://127.0.0.1:5591` via `REVIEW_API_PROXY_TARGET`) and runs one sequential journey per viewport against `http://127.0.0.1:5592` only.

Cleanup and evidence discipline:

- Global teardown removes `/tmp/medicore-e2e-h2`, so the disposable database never outlives the run.
- Playwright screenshots, video, and authenticated network traces are disabled for failure artifacts; traces can persist login request bodies. The list reporter and a password field cleared immediately after request dispatch provide diagnostics without retaining credential values.
- The runner deletes any pre-existing images under `docs/evidence/phase3/` once per run before the servers start, and the final (mobile) viewport run copies the complete three-image set there only after every assertion of both viewport runs has passed; a failed or partial run ships no images.
- Screenshots show only synthetic demo data (`Demo*`/`DEMO-*`); credentials appear nowhere in the output. Automated checks supplement, not replace, direct visual inspection.

## Patient Journey smoke and performance evidence

With the backend running (a disposable local database with the demo cohort enabled — the seeded professionals are what make appointment references resolvable), run the repeatable API smoke of the demonstrated care-operations journey:

```bash
BASE_URL=http://127.0.0.1:5501 RUNS=3 \
HOSPITAL_SMOKE_PASSWORD="<same disposable value supplied at backend startup>" \
./scripts/smoke-patient-journey.sh
```

Behavior of the script:

- Boots nothing itself — it preflights `/actuator/health` and the login, then requires the backend at `BASE_URL` (default `http://127.0.0.1:5501`).
- Credentials come only from the environment: `HOSPITAL_SMOKE_USERNAME` (default `admin`) and the required `HOSPITAL_SMOKE_PASSWORD` (the disposable value you started the backend with). Nothing is hardcoded; never use a real or shared secret.
- Per run it performs the fifteen-step care-operations journey, timing each step: login → synthetic patient create (`SMOKE-<timestamp>-<run>` MRN) → patient search → patient detail → appointment create (patient + seeded professional) → appointment list → admission create (server sets `ADMITTED`) → discharge transition (the server applies `ADMITTED → DISCHARGED` and stamps a nonblank `dischargedAt`, which the script requires) → emergency-visit create (server sets `WAITING`; the triage label is a neutral 1–5 demo value with no clinical meaning) → `IN_TREATMENT` → `CLOSED` transitions → invoice create (a per-run unique number `SMOKE-INV-<timestamp>-<run>` and display-only demo amount/currency — financial simulation only; server sets `DRAFT`) → `ISSUED` → `PAID` transitions → dashboard (all eleven numeric count keys required; totals must at least be consistent with the journey just executed, and no status-aware key is required to be zero because the seed contains fixtures in every bucket).
- It exits non-zero on any non-2xx response, any missing or malformed contract field (`accessToken`/`roles` at login, created-record ids, the server-returned lifecycle status on every create/transition, list arrays, the eleven dashboard count keys), or unresolvable reference; with no resolvable professional it fails by design with a hint to start with `MEDICORE_DEMO_SEED=true` — there is no skip-create mode.
- It cleans up nothing: every created record is synthetic and it must only ever target a disposable local database.
- On success it prints per-step min/median/max timings and `SMOKE RESULT: PASS`.

Dated baseline results, environment assumptions, the regression budget definition, and the dashboard-profiling note live in `docs/performance.md`. Re-run the same script after changes to spot regressions in the demonstrated workflow.

## PostgreSQL later
Use the `postgres` Spring profile only with runtime-provided `DB_URL`, `DB_USER`, and `DB_PASSWORD`. Docker and public deployment are outside this phase.
