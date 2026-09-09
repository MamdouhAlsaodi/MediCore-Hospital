# Implementation Status

MediCore is an educational, non-clinical training project — not certified medical software. This page states truthfully what the code does, what it does not do, and where the evidence lives (`docs/traceability.md`).

## Task status — docs/plan1.md roadmap (Tasks 1–13)

| Task | Status | Commit / PR | Evidence |
|---|---|---|---|
| 1 — Characterize Patient Journey contracts | Done | `53d9e62` | `PatientJourneyApiTest` (18 tests) |
| 2 — Frontend test tooling before features | Done | `a91fe9f` | Vitest + Testing Library gate; `npm test` |
| 3 — Normalize API contracts (DTOs, error mapping) | Done | `d4f0516` | DTO field pins; `GlobalExceptionHandler` `ApiError` shape |
| 4 — Verified appointment relationships | Done | `e3bbc66` | `AppointmentService` reference resolution tests (404/400) |
| 5 — Authenticated navigation shell | Done | `fbfa290` | `AppShell.test.jsx`; in-memory navigation, no router dependency |
| 6 — Patient list and search | Done | `15f20de` (#1) | `PatientsPage.test.jsx`; `GET /api/patients?q=` |
| 7 — Patient registration, detail, edit | Done | `33f2839` (#2) | `PatientForm.test.jsx` (14) + `PatientsPage.test.jsx` (14); create/edit contracts |
| 8 — Professional selection + scheduling | Done | `40323b3` (#3) | `AppointmentsPage.test.jsx` (10); staff directory + appointment create |
| 9 — Role-aware actions + security regression coverage | Done | `b49e549` (#4) | `authorization.js` map + `authorization.test.js` (6); `SecurityConfig` method-level write rules + `SecurityAuthorizationTest` (9) |
| 10 — ADMIN audit evidence screen | Done | `968d9c9` (#5) | `AuditPage.test.jsx`; ADMIN-only `GET /api/audit` |
| 11 — Idempotent synthetic demo data | Done | `016b56a` (#6) | `DemoDataInitializerTest` (5, incl. seeded-audit pins via GAPFIX-026); `MEDICORE_DEMO_SEED` opt-in |
| 12 — Repeatable performance evidence | Done | `6595c03` (#7) | `scripts/smoke-patient-journey.sh`; baseline in `docs/performance.md` |
| 13 — Portfolio + operational evidence | Done (worker level; pending supervisor acceptance review) | this docs change | `docs/patient-journey.md`, `docs/architecture/patient-journey.md`, `docs/traceability.md`, updated `README.md`/`docs/api.md`/`docs/runbook.md` |

## What the build demonstrates

A single end-to-end synthetic workflow: login (`POST /api/auth/login`, JWT bearer) → patient registration → search/detail → edit of the four permitted fields → appointment scheduling against verified patient/professional references → ADMIN-visible audit evidence. Enforced server-side RBAC (method-level write rules in `SecurityConfig`), stable DTO/error contracts, opt-in idempotent demo data, a repeatable smoke script, and dated single-workstation performance baselines. Full story: `docs/patient-journey.md`.

## Known gaps (carried, honest)

- **Duplicate MRN 500 gap — CLOSED in #9 (`d3526ab`).** `PatientService.create` pre-checks the MRN and `GlobalExceptionHandler` maps integrity violations to `409 Conflict` (`ApiError`, "Medical record number already exists"), pinned by `duplicateMrnCreateReturns409ConflictWithoutOverwritingOriginal` (backend suite 33/33).
- **Demo-seeded rows carry no audit events — CLOSED by MEDICORE-DEMO-SEED-AUDIT-GAPFIX-026 (uncommitted worker evidence, pending supervisor verification).** The opt-in seeder now records a CREATE audit event (actor `system`) through `AuditService` for every newly created record under the services' resource-type conventions — 7 events on a fresh seed (3 patients + 2 professionals + 2 appointments), zero on reruns — pinned by `DemoDataInitializerTest.seededCreatesProduceSystemActorAuditEvents`; backend suite 34/34, frontend 61/61.
- **Performance evidence is single-workstation.** The `docs/performance.md` baseline describes one developer sandbox, an in-memory H2, and a single-digit-row dataset. It is a regression tripwire only — never a capacity or production claim.
- **Legacy appointment rows.** Rows created before Task 4 may hold raw string references; they remain readable but are not verified references. No schema migration exists in this milestone.
- **No professional availability/eligibility model.** Scheduling consumes selection only; eligibility rules are deferred until the `StaffMember` model represents them.
- **Docs drift observed during the Task 13 accuracy pass — mostly closed by MEDICORE-STALE-DOCS-GAPFIX-025:** the `frontend/src/authorization.js` header now describes the enforced Task 9 matrix, `docs/security.md` now describes the env-only secrets model (the "checked-in JWT secret and development admin" wording removed at `cd2ba13` is gone), and `docs/patient-journey.md`, `docs/implementation-status.md`, and `docs/api.md` record the 409 contract. The two remaining flagged stale spots are closed by MEDICORE-DEMO-SEED-AUDIT-GAPFIX-026: the `frontend/src/authorization.test.js` comment now describes the enforced ADMIN/RECEPTIONIST-only appointment-create rule (comment only — assertions untouched, suite 61/61) and the `docs/architecture/patient-journey.md` duplicate-MRN paragraph records the 409 contract.
- One intermittent timeout in a frontend edit-view test was observed once during Task 12 (sandbox load flakiness on a forbidden path); it did not recur in the gate runs.

Resolved in published commits (former gaps, kept here so history reads honestly): the Task 9 method-level write gaps and the RECEPTIONIST staff-directory 403 were fixed by `b49e549` (#4); the Task 10 stale `authorization.test.js` pin was fixed by `968d9c9` (#5); the Task 8 session-expiry pass-through from the patient detail flow landed with `b49e549`.

## Explicitly NOT done

- **Pilot and everything it gates:** no real hospital data, no owner-decision record from `docs/plan1.md` §9, no jurisdiction/privacy (LGPD) review, no threat model, no backup/restore rehearsal, no observability or incident-response setup.
- **PostgreSQL migration rehearsal and restore evidence** — the profile exists; the Pilot-phase evidence does not.
- **Docker/containerization** — intentionally postponed; none present.
- **Browser/mobile runtime verification and screenshots** — the plan allows screenshots only after that verification pass; it has not been run. UI behavior is pinned by component tests and CSS instead.
- **Production security hardening:** no MFA/SSO, no refresh-token rotation, no secret manager, no rate limiting, no CSRF/CORS hardening review.
- **External integrations:** no HL7/FHIR, PACS, payment, or insurer-network work.
- **Any production-readiness or clinical-use claim.** This is a training/reference build only.

The project is complete as the requested **Training/Portfolio milestone**, pending the formal acceptance review that `docs/plan1.md` §10 defines as the stop gate after Task 13.
