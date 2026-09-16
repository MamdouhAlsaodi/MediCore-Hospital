# Tasks: MediCore Phase 4 — Production-Like Engineering & Operational Resilience

**Input**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`, `.specify/memory/constitution.md`

**Execution**: Complete in order. Each code task uses RED → confirm failure → minimal GREEN → focused gate → regression gate → append checkpoint. Stop immediately on a hard gate. `[P]` means only the listed file creation can happen in parallel; migration/security/contract integration remains serialized.

## Phase 1 — Baseline and safety

- [ ] T001 Assert branch/baseline/status and record tool versions in ignored `artifacts/phase4/pi-execution-report.md` without secrets.
- [ ] T002 Add local runtime/artifact/backup exclusions to `.gitignore` without hiding tracked source/evidence.
- [ ] T003 Write RED public-artifact scanner test and implement `scripts/phase4/check-public-artifacts.sh` over tracked candidates.
- [ ] T004 Run fresh `backend/mvn test`; record exact count and failures.
- [ ] T005 Run fresh `frontend/npm ci`, `npm test`, and `npm run build`; record exact count and failures.
- [ ] T006 Stop `BLOCKED` if baseline code gates fail for an unexplained reason; do not implement around a dirty baseline.

## Phase 2 — Schema authority

- [ ] T007 Add RED PostgreSQL-profile tests proving Flyway enabled, Hibernate validate, and environment-only credentials.
- [ ] T008 Add compatible Flyway dependency in `backend/pom.xml`; verify actual resolved artifact.
- [ ] T009 Create reviewed `V1__baseline_schema.sql` for accepted entities, tables, indexes, foreign keys, and version columns.
- [ ] T010 Create `V2__phase4_constraints.sql` for deterministic ownership/lifecycle constraints supported by accepted data.
- [ ] T011 Make `application-postgres.yml` use Flyway + validate; keep H2 lightweight profile explicit.
- [ ] T012 Run focused profile tests then full backend suite.

## Phase 3 — Real PostgreSQL migration evidence

- [ ] T013 Add Testcontainers dependency/profile and reusable `PostgresContainerSupport` without mocking persistence under test.
- [ ] T014 RED: empty PostgreSQL migration and schema metadata assertions.
- [ ] T015 GREEN: apply migrations, validate schema, assert no pending migration on second startup.
- [ ] T016 RED: unsafe database host/name and source-target probes for `migrate-disposable-postgres.sh`.
- [ ] T017 GREEN: implement fail-closed disposable target guard and migration wrapper.
- [ ] T018 Add synthetic Phase 3-shaped rehearsal fixture; compare allowlisted counts/relationships/invariants before/after.
- [ ] T019 Run PostgreSQL migration class repeatedly and full backend tests.

## Phase 4 — Typed time/money and branch zones

- [ ] T020 Inventory exact demonstrated String timestamp/money columns and public DTO shapes; record migration matrix.
- [ ] T021 RED: branch `ZoneId` validation, ordinary conversion, DST gap rejection, DST overlap policy, host-zone independence.
- [ ] T022 RED: appointment/admission/emergency timestamp and invoice decimal wire-compatibility round trips.
- [ ] T023 Add `V3__branch_time_zone_and_typed_workflow_values.sql` with deterministic known synthetic backfill and fail-on-unknown behavior.
- [ ] T024 Implement `Branch.timeZone` and narrow branch-zone conversion service with explicit boundary validation.
- [ ] T025 Replace demonstrated persistence timestamp Strings with unambiguous time types; keep DTO ISO strings explicit.
- [ ] T026 Replace simulated invoice persistence String amount with exact `BigDecimal`/numeric; keep `toPlainString()` wire format.
- [ ] T027 Update synthetic fixtures without introducing real names/data or host-time dependence.
- [ ] T028 Run focused H2 and PostgreSQL tests plus full backend suite.

## Phase 5 — Scope and concurrency

- [ ] T029 RED: department reads show only acting-branch rows and hide unknown/unowned legacy rows.
- [ ] T030 Replace unscoped department reads with assignment-derived repository/service scope.
- [ ] T031 Record global MRN/invoice uniqueness decision; preserve it unless a complete proven migration requires otherwise.
- [ ] T032 Create `V4__scope_and_concurrency_constraints.sql` for demonstrated relational invariants only.
- [ ] T033 RED: concurrent same-bed admission race on PostgreSQL.
- [ ] T034 GREEN: enforce exactly one legal bed/admission winner, typed conflict loser, zero partial rows/success audit for loser.
- [ ] T035 RED/GREEN: concurrent overlapping appointment create with the same winner/loser/no-partial guarantees.
- [ ] T036 Repeat races without sleeps; run authorization/isolation matrix and full backend suite.

## Phase 6 — Containers

- [ ] T037 RED: static checks for multi-stage/non-root images, no secret copy, health semantics, and Compose ordering.
- [ ] T038 [P] Create `backend/Dockerfile` with Java build/runtime stages and non-root runtime.
- [ ] T039 [P] Create `frontend/Dockerfile` and `frontend/nginx.conf` with SPA fallback, same-origin API routing, and security headers.
- [ ] T040 Create root `.dockerignore` and `.env.example` with no fallback secrets.
- [ ] T041 Create `compose.yaml`: PostgreSQL health → backend readiness → frontend health, loopback review exposure.
- [ ] T042 Create `scripts/phase4/wait-for-review-stack.sh` with bounded timeout and truthful diagnostics.
- [ ] T043 Run `docker compose config`, complete image builds, stack health, anonymous auth-boundary probe, and teardown.

## Phase 7 — Backup and restore

- [ ] T044 RED: target guard rejects unsafe host/name, source-target equality, existing restore target, missing/corrupt archive.
- [ ] T045 Implement `postgres-target-guard.sh` and share it between migration/backup/restore commands.
- [ ] T046 Implement `backup-postgres.sh`: custom format, restrictive permissions, checksum, no credential output.
- [ ] T047 Implement `restore-postgres.sh`: `pg_restore --list`, fresh disposable target only, fail closed.
- [ ] T048 Implement invariant verifier for migration version, allowlisted counts, references, ownership, lifecycle, and audit coverage.
- [ ] T049 Implement/run `test-backup-restore.sh`; prove source unchanged after negative probes.
- [ ] T050 Document measured local duration without SLA/RPO/RTO promise in `docs/runbooks/backup-restore.md`.

## Phase 8 — Observability

- [x] T051 RED: liveness survives DB loss while readiness fails; readiness recovers after DB returns.
- [x] T052 Add Actuator Prometheus dependencies and explicit minimal endpoint exposure.
- [x] T053 RED: structured log captures required bounded fields and rejects token/password/body/patient/resource canaries.
- [x] T054 Implement structured JSON logging using existing correlation boundary; no duplicate correlation authority.
- [x] T055 Add only low-cardinality HTTP/business metrics; add tests forbidding sensitive/unbounded labels.
- [x] T056 Run live DB loss/recovery plus captured log/metrics leakage tests.
- [x] T057 Write `docs/runbooks/observability.md` from actual endpoints/commands.

## Phase 9 — Security hardening

- [x] T058 Inventory all frontend POST/PUT/PATCH/DELETE callers and shared/raw fetch paths before global policy changes.
- [x] T059 RED matrix: anonymous, permitted roles, denied roles, forged role/branch headers, tampered/expired JWT, disabled user/assignment.
- [x] T060 RED matrix: generic login failure, repeated failures/rate limit, bounded-store eviction/expiry, recovery after window.
- [x] T061 Implement bounded expiring login rate limiter using direct socket address unless explicit trusted-proxy config exists.
- [x] T062 RED/GREEN explicit CORS allowlist/preflight and fail-closed production-like profile.
- [x] T063 RED/GREEN API and Nginx security headers; do not break same-origin review path.
- [x] T064 Verify actuator exposure and ensure denied requests create zero mutations and zero success audit events.
- [x] T065 Write `docs/security/threat-model-phase4.md`, including limitations and excluded refresh/MFA/SSO work.
- [x] T066 Run focused and full backend/frontend security regressions.

## Phase 10 — OpenAPI and typed frontend contract

- [x] T067 RED expected OpenAPI paths/schemas/statuses for auth, patient, appointment, admission, dashboard, audit. (R1 written; R2 fresh `OpenApiContractTest` 4/4 GREEN)
- [x] T068 Add compatible springdoc integration and narrow annotations without duplicating business validation. (R1 written; R2 fresh GREEN)
- [x] T069 Implement safe isolated `generate-openapi.sh` and write `api/openapi/medicore-v1.yaml` deterministically. (R1 written; R2 fresh exit 0)
- [x] T070 Prove two consecutive generation runs are byte-stable; do not hide semantic drift. (R2: two runs sha256-identical, 4 artifacts)
- [x] T071 Add deterministic TypeScript client generation under `frontend/src/generated/api/`; generated files are read-only outputs. (R1 written; R2 regenerated + `tsc --strict` exit 0)
- [x] T072 RED/GREEN transport tests for method/path/header/body and `400/401/403/404/409` contracts. (R1 written; R2 green in fresh frontend suite)
- [x] T073 Adapt existing shared API boundary and representative demonstrated callers; no broad UI rewrite. (R1 written; R2 green in fresh frontend suite)
- [x] T074 Implement `check-openapi-drift.sh`; prove stale artifact fails and regenerated artifact passes. (R2: stale exit 1 with diff, restored byte-identical, fresh exit 0)
- [ ] T075 Run backend contract tests, frontend tests/typecheck/build, and representative real HTTP contract tests. (R2: contract tests 4/4, frontend 251/251 + typecheck + build GREEN, live HTTP statuses proven; full backend suite 237 run / 1 error — pre-existing Phase 8 `LivenessReadinessDbLossIntegrationTest` PostgreSQL-container death, reproducible 3/3 on this memory-constrained host, untouched by the OpenAPI diff; see R2 report)

## Phase 11 — Full container journey and recovery

- [x] T076 Assert Playwright `trace: 'off'` and preserve no video/screenshot acceptance shortcut.
- [x] T077 Generate process-local synthetic credentials before loading Playwright config; never persist/log them.
- [x] T078 Start clean stack and prove service/port ownership before browser execution.
- [x] T079 RED/GREEN desktop journey: login → context → patient → appointment → admission/bed → discharge → emergency → simulated invoice → command-center → audit.
- [x] T080 RED/GREEN mobile viewport journey and one-line responsive navigation/overflow checks.
- [x] T081 Add tagged context-switch discrimination test proving no stale branch rows paint.
- [x] T082 Restart backend; verify zero pending migrations and preserved synthetic durable state.
- [x] T083 Stop PostgreSQL; verify liveness/readiness split; restore PostgreSQL and verify recovery.
- [x] T084 Assert exact expected non-2xx responses; reject unrelated console/page errors.

## Phase 12 — Canonical acceptance

- [ ] T085 Implement fail-fast `scripts/phase4/acceptance.sh` composing every required gate in dependency order.
- [ ] T086 Add cleanup traps restricted to named Phase 4 disposable containers/databases/artifacts.
- [ ] T087 Prove absent Docker/PostgreSQL cannot yield false PASS.
- [ ] T088 If repository CI policy permits, add `.github/workflows/phase4-quality.yml` without secrets; otherwise document local-only gate and do not fabricate CI.
- [ ] T089 Run full canonical acceptance on capable host; capture exact stage verdicts and totals.

## Phase 13 — Evidence and stop gate

- [ ] T090 Update README and runbooks from real commands only.
- [ ] T091 Update `docs/pdr.md` additively, `docs/implementation-status.md`, and `docs/traceability.md` with FR/SC evidence.
- [ ] T092 Create `docs/evidence/phase4-verification.md` mapping FR-001..020 and SC-001..010 to exact source/test/runtime proof.
- [ ] T093 Run public tracked-artifact scan; remove credentials, private topology, real personal data, unsupported clinical/production/compliance/SLA claims.
- [ ] T094 Run `git diff --check`; inventory tracked and untracked paths and reject unrelated/generated/runtime files.
- [ ] T095 Run `scripts/phase4/acceptance.sh` fresh as final stop gate.
- [ ] T096 Finalize ignored `artifacts/phase4/pi-execution-report.md` with exact report schema and terminal `PASS | PARTIAL | BLOCKED`.
- [ ] T097 Leave worktree intact and exit—no commit, push, PR, merge, deployment, release, or notification to external systems.

## Dependency graph

`T001–T006 → T007–T019 → T020–T036 → T037–T050 → T051–T066 → T067–T075 → T076–T084 → T085–T097`

Within the graph, only explicitly marked independent file creation may overlap. Shared dependencies, migrations, security, OpenAPI, and runtime gates are serialized.

## Final acceptance checklist

- [ ] Empty and rehearsal PostgreSQL migrations; restart idempotency.
- [ ] Typed demonstrated time/money persistence with wire compatibility and DST proof.
- [ ] Department branch scope and real PostgreSQL concurrency winners.
- [ ] Complete Compose builds, health, synthetic workflow, restart, dependency loss/recovery.
- [ ] Guarded backup/archive/checksum/restore/invariants.
- [ ] Structured sanitized logs, bounded metrics, liveness/readiness.
- [ ] Authentication/CORS/security-header/authorization matrix.
- [ ] Deterministic OpenAPI and generated TypeScript client drift check.
- [ ] Backend/frontend/build/E2E/canonical acceptance green with fresh exact counts.
- [ ] Traceability complete; no secret/private/real-data/unsupported claim finding.
- [ ] No forbidden Git/publication/deployment/destructive/non-synthetic side effect.
