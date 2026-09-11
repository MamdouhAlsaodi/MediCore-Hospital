# Implementation Status

MediCore is an educational, non-clinical training project — not certified medical software. This page states truthfully what the code does, what it does not do, and where the evidence lives (`docs/traceability.md`).

## Task status — docs/plan2.md roadmap (Tasks 1–10, care operations)

| Task | Status | Commit / PR | Evidence |
|---|---|---|---|
| 1 — Characterize care-operations contracts and RBAC baseline | Done | `5c5ae2b` (#15) | `CareOperationsApiTest` (now 20 tests) pinned the raw CRUD baseline before any write task |
| 2 — Admissions workflow with discharge lifecycle | Done | `49e93e6` (#16) | `AdmissionService` transition map; server-stamped `dischargedAt`; `AdmissionsPage.test.jsx` (14) |
| 3 — Emergency-visit workflow, neutral `1–5` demo triage label | Done | `ba4d8b4` (#17) | `EmergencyVisitService` transitions; `EmergencyVisitsPage.test.jsx` (14) |
| 4 — Invoice lifecycle (financial simulation, unique numbers) | Done | `2cd5c6a` (#18) | `InvoiceService` duplicate pre-check + DB unique constraint; `InvoicesPage.test.jsx` (11) |
| 5 — Status-aware operations dashboard | Done | `c01df1b` (#19) | `DashboardService` eleven-key aggregation; `DashboardApiTest`; `DashboardPage.test.jsx` (5) |
| 6 — Role-aware navigation / RBAC matrix alignment | Done | `134b9b6` (#20) | `SecurityAuthorizationTest` (14) care-operations matrix; `navigation.test.js` (11), `authorization.test.js` (11) |
| 7 — Audit coverage and failure-contract sweep | Done | `6042678` (#21) | `careOperationsAuditSweepPinsExactlyOneEventCanonicalDetailsAndNoSensitiveLeakage` and per-family failure pins |
| 8 — Synthetic fixture expansion | Done | `e777136` (#22) | `DemoDataInitializerTest` (8): composition, referential integrity, idempotency, system-actor events |
| 9 — Journey smoke extension and performance re-baseline | Done | `236dc3f` (#23) | 15-step smoke script; supervisor-recorded isolated live run `RUNS=5`, `SMOKE RESULT: PASS` — baseline in `docs/performance.md` |
| 10 — Documentation, portfolio evidence, and stop gate | Accepted | this docs change; acceptance snapshot `b9df613` | `docs/care-operations.md`, `docs/architecture/care-operations.md`, updated `docs/api.md`/`docs/traceability.md`/`docs/patient-journey.md`/`docs/runbook.md`/`README.md`. Acceptance at `b9df613`: independent clean-checkout verification — docs/source mechanical audit PASS; backend `mvn test` 70/70; frontend `npm test` 125/125 across 12 files and production build PASS with no CSS warnings; static smoke gate and executable mode PASS; checkout clean before and after. Supervisor-owned live smoke on the same snapshot: 15/15 journey steps, `RUNS=1`, `SMOKE RESULT: PASS`, exit 0, isolated loopback H2 (acceptance run, not a new performance baseline) |

Publication state: the Plan 2 implementation PRs (#15–#23) are merged into `main` as of 2026-09-10. Task 10 is accepted at snapshot `b9df613` by the formal stop-gate acceptance review, which ran from an independent clean checkout at that snapshot (concise evidence in the Task 10 row above and in `docs/traceability.md`).

## Task status — docs/plan1.md roadmap (Tasks 1–13, patient journey)

| Task | Status | Commit / PR | Evidence |
|---|---|---|---|
| 1 — Characterize Patient Journey contracts | Done | `53d9e62` | `PatientJourneyApiTest` (18 tests then; 19 in the current suite) |
| 2 — Frontend test tooling before features | Done | `a91fe9f` | Vitest + Testing Library gate; `npm test` |
| 3 — Normalize API contracts (DTOs, error mapping) | Done | `d4f0516` | DTO field pins; `GlobalExceptionHandler` `ApiError` shape |
| 4 — Verified appointment relationships | Done | `e3bbc66` | `AppointmentService` reference resolution tests (404/400) |
| 5 — Authenticated navigation shell | Done | `fbfa290` | `AppShell.test.jsx`; in-memory navigation, no router dependency |
| 6 — Patient list and search | Done | `15f20de` (#1) | `PatientsPage.test.jsx`; `GET /api/patients?q=` |
| 7 — Patient registration, detail, edit | Done | `33f2839` (#2) | `PatientForm.test.jsx` + `PatientsPage.test.jsx`; create/edit contracts |
| 8 — Professional selection + scheduling | Done | `40323b3` (#3) | `AppointmentsPage.test.jsx`; staff directory + appointment create |
| 9 — Role-aware actions + security regression coverage | Done | `b49e549` (#4) | `authorization.js` map + `authorization.test.js`; `SecurityConfig` method-level write rules + `SecurityAuthorizationTest` |
| 10 — ADMIN audit evidence screen | Done | `968d9c9` (#5) | `AuditPage.test.jsx`; ADMIN-only `GET /api/audit` |
| 11 — Idempotent synthetic demo data | Done | `016b56a` (#6) | `DemoDataInitializerTest`; `MEDICORE_DEMO_SEED` opt-in |
| 12 — Repeatable performance evidence | Done | `6595c03` (#7) | `scripts/smoke-patient-journey.sh`; baseline in `docs/performance.md` |
| 13 — Portfolio + operational evidence | Done | `634961d` (#8) | `docs/patient-journey.md`, `docs/architecture/patient-journey.md`, `docs/traceability.md` |

## What the build demonstrates

Two coherent synthetic workflows on one stack. **Patient journey:** login (JWT bearer) → patient registration → search/detail → edit of the four permitted fields → appointment scheduling against verified references → ADMIN-visible audit. **Care operations:** admission of the existing patient with server-stamped discharge → emergency visit with a neutral `1–5` demo triage label through legal transitions → uniquely numbered simulated invoice through `DRAFT → ISSUED → PAID` (or `VOID`) → status-aware eleven-key dashboard → audit evidence for every mutation. Server-enforced RBAC throughout (including the ADMIN/BILLING-only invoice boundary), stable DTO/error contracts, opt-in idempotent demo data, an extended repeatable smoke script, and a dated single-workstation baseline. Full stories: `docs/patient-journey.md` and `docs/care-operations.md`.

## Fresh verification evidence (at this documentation revision)

- Backend: `mvn test` — **70 tests, 0 failures, 0 skipped** (`CareOperationsApiTest` 20, `PatientJourneyApiTest` 19, `SecurityAuthorizationTest` 14, `DemoDataInitializerTest` 8, `DevAdminInitializerTest` 6, `DashboardApiTest` 2, `ArchitectureSmokeTest` 1).
- Frontend: `npm test` — **125 tests across 12 files, 0 failures**; `npm run build` — production bundle exit 0.
- Smoke: `scripts/smoke-patient-journey.sh` — supervisor-recorded isolated live run with `RUNS=5`, exit 0, `SMOKE RESULT: PASS` (`docs/performance.md` Task 9 baseline; single-workstation regression tripwire, not a capacity claim).

## Known gaps (carried, honest)

- **Duplicate MRN 500 gap — CLOSED in #9 (`d3526ab`).** `PatientService.create` pre-checks the MRN and `GlobalExceptionHandler` maps integrity violations to `409 Conflict`; the same cause-free-passthrough/generic-message discipline now backs duplicate invoice numbers (`InvoiceService` pre-check + `uk_invoices_invoice_number` constraint).
- **Performance evidence is single-workstation.** The `docs/performance.md` Task 9 baseline describes one developer sandbox, an in-memory H2, and a single-digit-row dataset. It is a regression tripwire only — never a capacity or production claim. The older Task 12 tables there are superseded (journey and dataset changed) and marked as history.
- **Legacy String columns.** Appointment, admission, emergency-visit, and invoice reference/time/amount columns remain Strings; new contracts resolve typed request values to canonical strings at the service boundary (UUID strings, `LocalDateTime.toString()`, `BigDecimal.toPlainString()`). No schema migration exists in this milestone.
- **Beds stay raw CRUD.** `/api/beds` keeps its entity-shaped responses and unverified string references; bed management was explicitly deferred (plan2.md §7.2) pending an owner decision.
- **No professional availability/eligibility model.** Scheduling consumes selection only; eligibility rules are deferred until the `StaffMember` model represents them.
- **Pre-Task-4 appointment rows** may hold raw string references; they remain readable but are not verified references.
- One intermittent timeout in a frontend edit-view test was observed once during the Task 12 era (sandbox load flakiness on a forbidden path); it did not recur in subsequent gate runs.

## Explicitly NOT done

- **Pilot and everything it gates:** no real hospital data, no jurisdiction/privacy review, no threat model, no backup/restore rehearsal, no observability or incident-response setup.
- **PostgreSQL migration rehearsal and restore evidence** — the profile exists; the Pilot-phase evidence does not.
- **Docker/containerization** — intentionally postponed; none present.
- **Browser/mobile runtime verification and screenshots** — the plan allows screenshots only after that verification pass; it has not been run. UI behavior is pinned by component tests and CSS instead.
- **Production security hardening:** no MFA/SSO, no refresh-token rotation, no secret manager, no rate limiting, no CSRF/CORS hardening review.
- **External integrations:** no HL7/FHIR, PACS, payment, or insurer-network work. The invoice family is a demo simulation with no payment processing, tax, FX, or collection; insurance claims remain raw CRUD out of scope.
- **Real triage semantics:** the emergency-visit `1–5` label is a neutral demo value — not ATS, ESI, MTS, or any clinical protocol; adopting a real scale would be an owner decision.
- **Any production-readiness or clinical-use claim.** This is a training/reference build only.

## Stop gate

Per `docs/plan2.md` §8: Plan 2 acceptance authorizes **Training/Portfolio demonstration only**. The work stops after Task 10. The formal acceptance review against the §4 Definition of Done (matrix in `docs/traceability.md`) is complete: it ran from an independent clean checkout at snapshot `b9df613` following only repository instructions, and Plan 2 is accepted. This acceptance closes Plan 2 only inside Training/Portfolio scope and authorizes no next phase. No Pilot, Phase 3, real data, real triage protocol, clinical integration, payment/insurer integration, or production-readiness claim may start without the owner's explicit, separate decision.
