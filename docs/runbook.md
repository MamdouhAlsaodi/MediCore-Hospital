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

What gets created (all values are obviously synthetic; no real personal, clinical, or financial data; Training/Portfolio use only):

- 3 patients: `Demo Patient Alpha` (`DEMO-0001`), `Demo Patient Bravo` (`DEMO-0002`), `Demo Patient Charlie` (`DEMO-0003`)
- 2 professionals: `Demo Physician Alpha` (`DEMO-STAFF-001`, internal medicine), `Demo Nurse Bravo` (`DEMO-STAFF-002`, nursing)
- 2 appointments linking them: Alpha ↔ Physician (consultation, scheduled) and Bravo ↔ Nurse (follow-up, confirmed)
- 2 admissions: Alpha `ADMITTED` (open) and Bravo `DISCHARGED`, with generic demo workflow labels as reasons
- 3 emergency visits: Charlie `WAITING`, Alpha `IN_TREATMENT`, Bravo `CLOSED`, with generic demo complaint labels and the meaningless demo triage labels `2`–`4` (triage here is never a clinical assessment)
- 4 invoices (`DEMO-INV-0001`–`DEMO-INV-0004`), exactly one in each state `DRAFT`, `ISSUED`, `PAID`, `VOID` — amounts and currency labels are display-only financial simulation data with no payment semantics

Expected review evidence after startup: the Dashboard shows `patients=3`, `appointments=2`, `admissions=2`, `emergencyVisits=3`, `invoices=4`, `openAdmissions=1` (the DISCHARGED row is excluded), `activeEmergencyVisits=2` (WAITING + IN_TREATMENT; CLOSED is excluded), and exactly `1` in each `invoicesDraft`/`invoicesIssued`/`invoicesPaid`/`invoicesVoid` bucket.

Seeding is idempotent and safe to restart: every insert is lookup-before-create (patient MRN, professional employee code, appointment key, admission patient+time+reason key, emergency patient+arrival+complaint key, unique invoice number), so restarting never duplicates rows, and it never deletes or modifies existing records. Every newly created record is recorded as a CREATE audit event attributed to the `system` actor (no user is authenticated at startup), so the ADMIN Audit screen shows the full seeded journey; reused records add no events, which keeps restarts idempotent in the audit trail too. No accounts or credentials are created.

Verify by logging in as an ADMIN and checking Patients (search `DEMO-`), Appointments, Admissions, Emergency visits, Invoices, the Dashboard totals above, and the Audit screen (CREATE events with actor `system`, one per newly created seeded record — sixteen in total for the full cohort: 3 patients + 2 professionals + 2 appointments + 2 admissions + 3 emergency visits + 4 invoices). Never enable demo seeding against a shared or production data store.

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
cd backend && mvn test                        # 70 tests (CareOperationsApiTest 20,
                                              # PatientJourneyApiTest 19,
                                              # SecurityAuthorizationTest 14,
                                              # DemoDataInitializerTest 8,
                                              # DevAdminInitializerTest 6,
                                              # DashboardApiTest 2,
                                              # ArchitectureSmokeTest 1)
cd ../frontend && npm test && npm run build   # 125 tests across 12 files + production build
cd .. && git diff --check                     # whitespace/conflict-marker gate
```

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
