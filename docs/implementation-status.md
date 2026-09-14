# Implementation Status

MediCore is an educational, non-clinical training project — not certified medical software. This page states truthfully what the code does, what it does not do, and where the evidence lives (`docs/traceability.md`). Since Phase 3 it demonstrates **one synthetic organization with three synthetic branches**; that is a Training/Portfolio scope boundary, not SaaS multi-tenancy.

## Task status — docs/plan3.md roadmap (Tasks 1–15, multi-branch operations)

| Task | Status | Commit / PR | Evidence |
|---|---|---|---|
| 1 — Characterize branchless contracts and security baseline | Done | `c67c26d` (#26) | `MultiBranchOperationsApiTest` created; `SecurityAuthorizationTest` extended to pin the global-role, branchless baseline before any change |
| 2 — Organization, branches, branch-owned departments | Done | `c214b00` (#27) | `HospitalOrganization`/`Branch` + normalized `DepartmentService`; ADMIN-only hierarchy routes; duplicate codes `409`; legacy unassigned rows hidden |
| 3 — Acting assignments and branch-bound auth context | Done | `48ac07b` (#28) | `ActingContextService`/`BranchAccessService`/`JwtFilter` reload; login `Session` with assignments + actingContext; `POST /api/auth/context`; fail-closed refusals pinned |
| 4 — Branch-scoped patient, staff, and appointment workflow | Done | `6e447a8` (#29) | `PatientService`/`StaffMemberRepository`/`AppointmentService` branch-parameterized queries; cross-branch `404`; server-stamped ownership |
| 5 — Branch-aware shell and context selector | Done | `5691a05` (#32) | `BranchSelector.jsx` (server-issued targets only); shell identity/role/branch line; context-key remount; component tests + focused backend reruns |
| 6 — Normalized branch-owned bed inventory | Done | `73c3fd2` (#30) | `BedService`/`BedDtos` lifecycle; unique (branch, ward, room, bedNumber); concurrency winner tests; `BedsPage.jsx` |
| 7 — Atomic admission bed assignment, transfer, release | Done | `3308f4a` (#31) | `AdmissionBedAssignment` row; one-transaction occupy/transfer/release; rollback and repeat-transition pins; `PUT /api/admissions/{id}/bed` |
| 8 — Branch-scoped emergency and invoice workflows | Done | `c73e7f1` (#33) | Per-workflow branch-scope pins in `MultiBranchOperationsApiTest` + `CareOperationsApiTest`; lifecycles unchanged |
| 9 — Staff availability and appointment conflict prevention | Done | `50c435a` (#34) | `StaffAvailability` service with pessimistic lock; appointment containment/overlap `409`; adjacency legal; UI availability display |
| 10 — Branch and network command centers | Done | `7a1ff44` (#35) | `DashboardDtos.BranchSummary`/`NetworkSummary`; `/api/dashboard/branch`, `/api/dashboard/network` (ORGANIZATION-ADMIN only), deprecated alias; drill-downs |
| 11 — Audit context and authorization sweep | Done | `dc8737c` (#36) | `AuditEvent` acting-context columns; `CorrelationIdFilter` (bounded `X-Correlation-Id`); scope-aware filtered `AuditController`; exactly-once/no-event pins |
| 12 — Idempotent three-branch synthetic cohort | Done | `942bb6b` (#37) | `DemoDataInitializerTest` (13): composition, referential coherence, zero-duplicate re-seed, 54 system events first run; runbook composition |
| 13 — Responsive accessible browser evidence | Done | `620d107` (#38) | Pinned Playwright 1.61.0; desktop 1280×720 + mobile 375×812 journeys; screenshots published only on full pass (`docs/evidence/phase3/` + inspection record) |
| 14 — Extended smoke journey and regression baseline | Done | `aefc861` (#39) | 36-step branch-aware smoke (isolation proof + required `409`); dated Task 14 baseline in `docs/performance.md` (tripwire only) |
| 15 — Publish Phase 3 architecture, evidence, and formal stop gate | Accepted at this docs revision | this change | `docs/multi-branch-operations.md`, `docs/architecture/multi-branch-operations.md`, updated `docs/api.md`/`docs/traceability.md`/`docs/patient-journey.md`/`docs/care-operations.md`/`docs/runbook.md`/`README.md`; the 14-row acceptance map with fresh evidence is in `docs/traceability.md` (Plan 3 section) |

Publication state: the Plan 3 implementation PRs (#26–#39) are merged into `main`; the plan itself merged as #25. Task 15 is the documentation-and-stop-gate change; its fresh verification evidence is the next section, and the formal acceptance row map is in `docs/traceability.md`.

## Task status — docs/plan2.md roadmap (Tasks 1–10, care operations)

| Task | Status | Commit / PR | Evidence |
|---|---|---|---|
| 1 — Characterize care-operations contracts and RBAC baseline | Done | `5c5ae2b` (#15) | `CareOperationsApiTest` pinned the raw CRUD baseline before any write task |
| 2 — Admissions workflow with discharge lifecycle | Done | `49e93e6` (#16) | `AdmissionService` transition map; server-stamped `dischargedAt`; `AdmissionsPage.test.jsx` |
| 3 — Emergency-visit workflow, neutral `1–5` demo triage label | Done | `ba4d8b4` (#17) | `EmergencyVisitService` transitions; `EmergencyVisitsPage.test.jsx` |
| 4 — Invoice lifecycle (financial simulation, unique numbers) | Done | `2cd5c6a` (#18) | `InvoiceService` duplicate pre-check + DB unique constraint; `InvoicesPage.test.jsx` |
| 5 — Status-aware operations dashboard | Done | `c01df1b` (#19) | Superseded in Phase 3 by the typed branch/network command centers (`DashboardApiTest` 7 tests) |
| 6 — Role-aware navigation / RBAC matrix alignment | Done | `134b9b6` (#20) | `SecurityAuthorizationTest` (38 in the current suite) |
| 7 — Audit coverage and failure-contract sweep | Done | `6042678` (#21) | Audit sweep pins; extended in Phase 3 with acting-context columns and the correlation-id boundary |
| 8 — Synthetic fixture expansion | Done | `e777136` (#22) | Superseded in Phase 3 by the three-branch cohort (`DemoDataInitializerTest` 13) |
| 9 — Journey smoke extension and performance re-baseline | Done | `236dc3f` (#23) | Superseded in Phase 3 by the branch-aware 36-step journey (`docs/performance.md` Task 14 baseline) |
| 10 — Documentation, portfolio evidence, and stop gate | Accepted | acceptance snapshot `b9df613` | Phase 2 evidence preserved in `docs/traceability.md` (Plan 2 section) |

## Task status — docs/plan1.md roadmap (Tasks 1–13, patient journey)

| Task | Status | Commit / PR | Evidence |
|---|---|---|---|
| 1 — Characterize Patient Journey contracts | Done | `53d9e62` | `PatientJourneyApiTest` (24 tests in the current suite) |
| 2 — Frontend test tooling before features | Done | `a91fe9f` | Vitest + Testing Library gate; `npm test` |
| 3 — Normalize API contracts (DTOs, error mapping) | Done | `d4f0516` | DTO field pins; `GlobalExceptionHandler` `ApiError` shape |
| 4 — Verified appointment relationships | Done | `e3bbc66` | Reference resolution tests; extended in Phase 3 with branch scope and availability |
| 5 — Authenticated navigation shell | Done | `fbfa290` | `AppShell.test.jsx`; extended in Phase 3 with the acting-context selector |
| 6 — Patient list and search | Done | `15f20de` (#1) | `PatientsPage.test.jsx`; branch-scoped `GET /api/patients?q=` |
| 7 — Patient registration, detail, edit | Done | `33f2839` (#2) | `PatientForm.test.jsx` + `PatientsPage.test.jsx`; create/edit contracts |
| 8 — Professional selection + scheduling | Done | `40323b3` (#3) | `AppointmentsPage.test.jsx`; staff directory + availability-aware scheduling |
| 9 — Role-aware actions + security regression coverage | Done | `b49e549` (#4) | `authorization.js` map + tests; `SecurityConfig` rules + `SecurityAuthorizationTest` |
| 10 — ADMIN audit evidence screen | Done | `968d9c9` (#5) | `AuditPage.test.jsx`; scope-aware ADMIN-only `GET /api/audit` |
| 11 — Idempotent synthetic demo data | Done | `016b56a` (#6) | Superseded in Phase 3 by the three-branch cohort |
| 12 — Repeatable performance evidence | Done | `6595c03` (#7) | `scripts/smoke-patient-journey.sh`; Task 14 baseline in `docs/performance.md` |
| 13 — Portfolio + operational evidence | Done | `634961d` (#8) | `docs/patient-journey.md`, architecture docs, `docs/traceability.md` |

## What the build demonstrates

Three coherent synthetic stories on one stack. **Patient journey:** login and acting-context selection → patient registration inside the acting branch → search/detail → edit of the four permitted fields → availability-aware appointment scheduling with server-enforced conflict rejection → scope-aware audit. **Care operations:** admission of the same-branch patient into an available bed with atomic bed transfer and discharge/release → emergency visit with a neutral `1–5` demo triage label through legal transitions → uniquely numbered simulated invoice through `DRAFT → ISSUED → PAID` (or `VOID`) → branch and network command centers → context-attributed audit evidence. **Multi-branch operations:** identity separated from acting assignment; a server-verified, token-bound acting context on every request; cross-branch isolation on every migrated workflow (reads, writes, references, deletes); normalized bed inventory with a transactional admission lifecycle; staff availability with deterministic overlap rejection; organization-scoped network comparison; and correlation-id-carrying audit rows. Server-enforced RBAC throughout (including the ADMIN/BILLING-only invoice boundary and the ORGANIZATION-ADMIN-only network view), stable DTO/error contracts, an idempotent three-branch demo cohort, automated browser journeys at two viewports, and a 36-step repeatable smoke. Full stories: `docs/patient-journey.md`, `docs/care-operations.md`, and `docs/multi-branch-operations.md`.

## Fresh verification evidence (at this documentation revision)

All gates were run from this clean checkout (baseline `e7c63a0`) on one workstation; commands and results are recorded in `docs/traceability.md`:

- Backend: `mvn -q test` — **162 tests, 0 failures, 0 errors, 0 skipped** (`MultiBranchOperationsApiTest` 43, `SecurityAuthorizationTest` 38, `PatientJourneyApiTest` 24, `CareOperationsApiTest` 23, `DemoDataInitializerTest` 13, `DevAdminInitializerTest` 13, `DashboardApiTest` 7, `ArchitectureSmokeTest` 1).
- Frontend: `npm test` — **237 tests across 15 files, 0 failures**; `npm run build` — production bundle exit 0.
- Browser: `npm run test:e2e` — **2/2 viewport journeys passed** (desktop 1280×720, mobile 375×812) against the disposable loopback review pair (`127.0.0.1:5591`/`5592`); the disposable H2 store was removed in teardown, and the three-image evidence set was regenerated only because both journeys passed (the committed Task 13 set is unchanged in this docs-only change).
- Live smoke: isolated loopback H2 backend (`jdbc:h2:mem:smoke;MODE=PostgreSQL`, repository-default DDL, `MEDICORE_DEMO_SEED=true`) on `127.0.0.1:5577` with process-local random credentials — **36/36 steps, `SMOKE RESULT: PASS (runs=1, total_ms=11977)`, exit 0**; the backend was stopped afterwards (port verified closed, temp boot log removed). Dated repeated-run baseline: `docs/performance.md`.
- `bash -n scripts/smoke-patient-journey.sh` and `git diff --check` — clean.

## Known gaps (carried, honest)

- **Legacy String columns.** Appointment, admission, emergency-visit, and invoice reference/time/amount columns remain Strings; contracts resolve typed request values to canonical strings at the service boundary (UUID strings, `LocalDateTime.toString()`, `BigDecimal.toPlainString()`). No schema migration exists in this milestone; unknown legacy rows stay hidden rather than migrated.
- **MRN and invoice-number uniqueness are global.** Branch scope is an ownership boundary, not a uniqueness scope, for these two keys — an explicit, documented contract rather than an oversight.
- **Performance evidence is single-workstation.** The `docs/performance.md` Task 14 baseline describes one developer sandbox, an isolated H2, and a tiny synthetic dataset. It is a regression tripwire only — never a capacity or production claim. The Task 9 and Task 12 tables there are history.
- **Concurrency defenses are JPA/H2-bounded.** Pessimistic locks, unique constraints, and optimistic versioning are tested against local fixtures; they prove transactional workflow correctness, not distributed or production concurrency behavior.
- **Unrelated raw-CRUD families remain.** Clinical encounters, nursing observations, lab/radiology orders, drugs, medication orders, surgeries, insurance claims, inventory, blood units, diet orders, facility work orders, documents, notifications, and shifts are untouched repository CRUD outside the demonstrated workflows.
- **Department hierarchy surface is route-authorized, not branch-scoped.** `GET /api/departments` lists assigned rows for its ADMIN/HR audience without acting-branch filtering; scoping that surface is future work, and unassigned rows are never disclosed anywhere.
- **Time zones.** Contracts use ISO timestamps and server UTC; branch-local time zones and DST policy need a later owner decision.
- One intermittent timeout in a frontend edit-view test was observed once during the Task 12 era (sandbox load flakiness); it did not recur in subsequent gate runs, including this revision's.

## Explicitly NOT done

- **Pilot and everything it gates:** no real hospital data, no jurisdiction/privacy review, no threat model, no backup/restore rehearsal, no observability or incident-response setup.
- **PostgreSQL migration rehearsal and restore evidence** — the profile exists; the Pilot-phase evidence does not.
- **Docker/containerization** — intentionally postponed; none present.
- **SaaS multi-tenancy:** no tenant resolver, tenant provisioning, per-tenant configuration, or cross-organization sharing; branches partition one synthetic organization only, and no tenant-isolation claim is made.
- **Production security hardening:** no MFA/SSO, no refresh-token rotation, no secret manager, no rate limiting, no CSRF/CORS hardening review.
- **External integrations:** no HL7/FHIR, PACS, payment, or insurer-network work. The invoice family is a demo simulation with no payment processing, tax, FX, or collection; insurance claims remain raw CRUD out of scope.
- **Real triage semantics:** the emergency-visit `1–5` label is a neutral demo value — not ATS, ESI, MTS, or any clinical protocol.
- **Calendaring breadth, leave management, payroll, credentialing, or clinical staff-eligibility policy** — availability is a dated interval model only.
- **Bed-capacity forecasting, staffing recommendations, predictive analytics, SLA/SLO claims, or production capacity claims.**
- **Any production-readiness or clinical-use claim.** This is a training/reference build only.

## Stop gate

Per `docs/plan3.md` §10: Phase 3 acceptance authorizes **a synthetic Training/Portfolio, single-organization, multi-branch demonstration only**. The formal acceptance review against the §5 Definition of Done (14-row map in `docs/traceability.md`) is recorded at this documentation revision with fresh evidence, and Phase 3 is accepted inside that scope. This closes Phase 3 and the roadmap: no Pilot, no later phase, no real data, no SaaS tenancy, no clinical use, no payment/insurer integration, no PostgreSQL migration rehearsal, no production deployment, and no production-readiness, capacity, SLA, or security-certification claim may start without the owner's explicit, separate decision.
