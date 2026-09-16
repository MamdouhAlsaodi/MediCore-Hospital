# MediCore Phase 4 — Production-Like Engineering & Operational Resilience Implementation Plan

> **Execution contract:** The owner explicitly requested one end-to-end Pi execution of this approved Phase 4 plan. Pi owns implementation, focused/full tests, integration, and its final report for all numbered stages below. Yui does not edit or poll while Pi runs and is notified only on terminal success/failure. Pi must stop with `BLOCKED` rather than improvise across a product, secret, destructive, publication, or real-data boundary.

**Goal:** Turn the accepted Phase 3 synthetic Training/Portfolio system into a reproducible, migration-controlled, observable, secure, contract-driven PostgreSQL review environment with verified backup/restore and failure behavior.

**Architecture:** Keep the Spring Boot modular monolith and React SPA. Add Flyway as PostgreSQL schema authority, Testcontainers for database-semantic proof, container images plus a Compose review stack, native PostgreSQL backup/restore tooling, bounded Micrometer/structured logging, explicit branch-zone time conversion, and generated OpenAPI/TypeScript contracts. Preserve existing REST behavior and authorization while replacing demonstrated workflow persistence primitives behind DTO compatibility mappers.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Security/JPA/Actuator, Flyway, PostgreSQL, Testcontainers, Micrometer Prometheus, springdoc-openapi, React 19, TypeScript 5.8, Vite 7, Vitest, Playwright, Docker Compose.

**Baseline:** `v0.3.0-phase3-stable` at `1b59a44b4729b9e1ff88d6822f153f4bd56ab3ac`.

**Safety:** Synthetic data only. Never read protected runtime env files, print secrets/URLs containing credentials, target a non-disposable database, commit/push/deploy/release, or claim clinical/production/compliance readiness.

---

## 0. Invariants and phase stop gates

- Existing server-derived acting assignment, organization, branch, roles, audit actor, and cross-branch non-disclosure remain authoritative.
- Existing synthetic journey remains coherent: patient → appointment → admission/bed → discharge; emergency; simulated invoice; command-center; audit.
- Unknown legacy rows are never guessed, auto-adopted, or widened into visibility.
- PostgreSQL destructive commands run only after an executable target-name/host guard accepts a disposable target.
- `ddl-auto=update` never runs under the PostgreSQL review profile.
- Any task with a red migration, security, authorization, backup/restore, contract-drift, or canonical acceptance gate stops the plan and reports `BLOCKED`.
- Passing H2 tests cannot substitute for PostgreSQL claims; passing builds cannot substitute for runtime proof.

Each stage appends to `artifacts/phase4/pi-execution-report.md` with: stage, changed paths, RED evidence, GREEN commands, exact exit statuses/test counts, risks, and `PASS | PARTIAL | BLOCKED`. The artifact is local operational evidence and must be ignored by Git.

## Task 1 — Baseline lock and dependency topology

**Objective:** Prove Phase 3 is green, record architecture/dependency baselines, and create safe execution/report scaffolding before production changes.

**Files:**
- Modify: `.gitignore`
- Create: `artifacts/phase4/pi-execution-report.md` (ignored, local)
- Create: `scripts/phase4/check-public-artifacts.sh`
- Modify later only when evidence exists: `docs/implementation-status.md`

**Steps:**
1. Assert HEAD equals the recorded baseline ancestor and branch is `phase4/production-like-resilience`; do not reset planning artifacts.
2. Record `git status --short`, Java/Maven/Node/npm/Docker versions, and existing test inventory without secrets.
3. Add `/artifacts/` and local env/backup outputs to `.gitignore` if not already covered.
4. Write a failing public-artifact check proving credential-like runtime files/private topology are rejected from tracked candidates, then implement the smallest script using tracked-file input—not a blind whole-home scan.
5. Run baseline gates serially:
   - `cd backend && mvn test`
   - `cd frontend && npm ci && npm test && npm run build`
6. Record exact totals. Existing historical totals (162 backend / 237 frontend) are reference only; fresh output is authority.

**Gate:** All baseline suites/build pass and no unexpected pre-existing source delta exists beyond approved planning/tool files.

## Task 2 — Flyway schema authority and PostgreSQL profiles

**Objective:** Replace PostgreSQL runtime schema mutation with versioned migrations and deterministic validation.

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-postgres.yml`
- Create: `backend/src/main/resources/db/migration/V1__baseline_schema.sql`
- Create: `backend/src/main/resources/db/migration/V2__phase4_constraints.sql`
- Create/modify focused configuration tests under `backend/src/test/java/com/mamtrex/hospital/`

**Steps:**
1. Add tests that load the PostgreSQL profile and fail unless Flyway is enabled, Hibernate is `validate`, and credentials are environment-only.
2. Add Spring Boot Flyway dependency only after confirming compatible managed versions.
3. Create explicit baseline schema from accepted entities/indexes/constraints. Do not generate a blind Hibernate dump without reviewing names/types/ownership.
4. Add forward-only constraints/indexes needed to match accepted Phase 3 invariants.
5. Keep H2 developer/test behavior explicitly isolated; never let one profile silently inherit unsafe values from another.
6. Run focused config tests and full backend tests.

**Gate:** Profile test proves Flyway + validate; no PostgreSQL profile uses update/create/drop; secrets absent from tracked config.

## Task 3 — PostgreSQL migration harness and rehearsals

**Objective:** Prove empty-schema migration, restart idempotency, and a documented Phase 3 synthetic rehearsal.

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/test/java/com/mamtrex/hospital/infrastructure/PostgresContainerSupport.java`
- Create: `backend/src/test/java/com/mamtrex/hospital/infrastructure/FlywayPostgresIntegrationTest.java`
- Create: `scripts/phase4/migrate-disposable-postgres.sh`
- Create: `scripts/phase4/verify-database-invariants.sh`

**Steps:**
1. Write RED Testcontainers tests for migrate-from-empty, second-start zero pending migrations, and required schema/index/constraint metadata.
2. Implement one reusable container fixture; do not mock persistence infrastructure under test.
3. Make shell guard accept only loopback/Testcontainers or the documented Compose service and a database name matching an explicit disposable prefix. Fail before any mutation otherwise.
4. Add a rehearsal fixture that represents the accepted synthetic Phase 3 shape and checks allowlisted counts/relationships before/after migration without printing rows.
5. Run the PostgreSQL integration class repeatedly and full backend suite.

**Gate:** Empty and rehearsal paths pass; second startup is idempotent; unsafe target probe fails closed.

## Task 4 — Typed branch time and demonstrated workflow values

**Objective:** Remove ambiguous time/money persistence from the demonstrated workflow while preserving the Phase 3 wire contract.

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__branch_time_zone_and_typed_workflow_values.sql`
- Modify exact domain/DTO/repository/service files in:
  - `backend/src/main/java/com/mamtrex/hospital/organization/`
  - `backend/src/main/java/com/mamtrex/hospital/appointment/`
  - `backend/src/main/java/com/mamtrex/hospital/admission/`
  - `backend/src/main/java/com/mamtrex/hospital/emergency/`
  - `backend/src/main/java/com/mamtrex/hospital/billing/`
  - `backend/src/main/java/com/mamtrex/hospital/bootstrap/DemoDataInitializer.java`
- Add corresponding tests under matching `backend/src/test/java/...` packages.

**Steps:**
1. Enumerate current columns/DTOs and write RED tests for round-trip compatibility, invalid IANA zone, DST gap, DST overlap, exact decimal, and existing appointment overlap.
2. Add `Branch.timeZone` using `ZoneId` validation. Backfill only known synthetic branch fixtures deterministically; unknown data must block non-null enforcement rather than use host time.
3. Migrate demonstrated timestamps to unambiguous Java/database types and invoice amount to `BigDecimal`/`numeric(19,2)`.
4. Preserve JSON strings using explicit mappers (`Instant` ISO form, decimal `toPlainString`) and stable error behavior.
5. Preserve half-open appointment windows and transaction boundaries.
6. Run focused H2 tests, PostgreSQL migration tests, full backend suite.

**Gate:** Wire compatibility and time/money boundary tests pass on PostgreSQL; no default JVM time zone can change persisted instants.

## Task 5 — Scope and relational constraint hardening

**Objective:** Close remaining hierarchy/read scope and concurrency holes with service/repository/database evidence.

**Files:**
- Modify department repository/controller/service/tests under `backend/src/main/java|test/java/com/mamtrex/hospital/organization/`
- Modify only proven constraint/locking targets under appointment/admission/bed packages.
- Create: `backend/src/main/resources/db/migration/V4__scope_and_concurrency_constraints.sql`
- Create focused PostgreSQL tests under `backend/src/test/java/com/mamtrex/hospital/infrastructure/`

**Steps:**
1. Add RED request/repository tests showing department reads return only acting-branch rows and unknown legacy rows remain hidden.
2. Replace any unscoped read with assignment-derived branch scope; never accept branch authority from query/body/header.
3. Inventory global uniqueness for MRN and invoice number. Preserve global uniqueness by default; do not widen/change scope without proven collision migration and compatibility need.
4. Add PostgreSQL constraints/indexes that encode demonstrated legal invariants.
5. Add real concurrent races: same-bed admission and overlapping appointment create. Assert exactly one legal winner, typed `409` loser, no partial rows, and no false successful audit event.
6. Repeat race tests enough to detect timing-sensitive failures without sleeps.

**Gate:** Isolation/security matrix and PostgreSQL races pass repeatedly.

## Task 6 — Container images and Compose review stack

**Objective:** Provide a deterministic one-command synthetic review environment.

**Files:**
- Create: `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`
- Create: `compose.yaml`, `.dockerignore`, `.env.example`
- Create: `scripts/phase4/wait-for-review-stack.sh`
- Create/modify container/health tests or scripts under `scripts/phase4/`
- Modify: `README.md`

**Steps:**
1. Write RED static/config checks for non-root runtime users, multi-stage builds, pinned major images, no copied secrets, correct dependency ordering, and health endpoints.
2. Build minimal runtime images. Frontend must provide SPA fallback and same-origin `/api` routing; runtime config must not embed credentials.
3. Compose PostgreSQL → backend readiness → frontend health; publish review ports explicitly to loopback unless documentation says otherwise.
4. Use environment placeholders without fallback passwords; `.env.example` contains names/descriptions only.
5. Run `docker compose config`, build each image fully to final export, start stack, verify health and anonymous auth boundary, then tear down.

**Gate:** Fresh stack reaches healthy state and images exist; config/build success is not accepted without HTTP/runtime health.

## Task 7 — Backup, restore, and invariant verification

**Objective:** Produce a safe, reproducible backup/restore rehearsal for synthetic PostgreSQL.

**Files:**
- Create: `scripts/phase4/postgres-target-guard.sh`
- Create: `scripts/phase4/backup-postgres.sh`
- Create: `scripts/phase4/restore-postgres.sh`
- Modify: `scripts/phase4/verify-database-invariants.sh`
- Create: `scripts/phase4/test-backup-restore.sh`
- Create: `docs/runbooks/backup-restore.md`

**Steps:**
1. RED-test guard refusal for unsafe host/name/source-target equality, missing archive, and corrupt archive.
2. Backup with native custom format, restrictive permissions, checksum, and no credential echo.
3. Validate archive using `pg_restore --list` before restore.
4. Create/restore only an explicitly disposable fresh target; refuse overwrite by default.
5. Compare allowlisted table counts, required references, lifecycle invariants, migration version, and audit coverage; never compare by dumping row bodies to logs.
6. Record measured duration only as local training evidence—not RPO/RTO/SLA.
7. Prove corrupt archive and unsafe-target failures leave the source unchanged.

**Gate:** Backup/checksum/archive/restore/invariants pass and all negative safety probes fail closed.

## Task 8 — Observability and truthful health semantics

**Objective:** Add useful local operational telemetry without sensitive or high-cardinality leakage.

**Files:**
- Modify: `backend/pom.xml`, application resource profiles
- Modify/create logging/health/metrics code under `backend/src/main/java/com/mamtrex/hospital/audit|infrastructure/`
- Create tests under matching test packages
- Create: `docs/runbooks/observability.md`

**Steps:**
1. RED-test liveness/readiness separation, correlation ID propagation, structured fields, and prohibited telemetry leakage.
2. Add Actuator Prometheus registry and explicitly expose only required review endpoints.
3. Keep `/actuator/health/liveness` process-only; readiness includes database/Flyway readiness.
4. Emit structured JSON logs with timestamp, level, logger, method, route template, status, duration bucket, correlation ID; never token/password/request-body/patient/resource values.
5. Add bounded business/HTTP metrics only where a stable low-cardinality label set exists. No user/branch/patient/assignment/resource/correlation ID metric labels.
6. Verify database loss/recovery changes readiness accurately while liveness remains truthful.

**Gate:** Telemetry tests and live dependency-loss recovery pass; sensitive-value canaries do not appear in captured logs/metrics.

## Task 9 — Authentication and HTTP security hardening

**Objective:** Harden login and browser/API boundaries without altering server-owned authority.

**Files:**
- Modify: `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java`
- Modify narrowly scoped auth/filter/controller files under `backend/src/main/java/com/mamtrex/hospital/auth/`
- Modify resource profiles for explicit CORS/security values
- Add focused auth/security tests under `backend/src/test/java/com/mamtrex/hospital/auth/`
- Create: `docs/security/threat-model-phase4.md`

**Steps:**
1. Inventory every frontend unsafe method caller before changing global CORS/headers.
2. RED-test anonymous `401`, authenticated denied `403`, generic login failure, tampered/expired JWT, disabled user, disabled assignment, role/header forgery, bad Origin/preflight, security headers, actuator exposure, and rate-limit recovery.
3. Implement bounded expiring login rate limiter with finite entry cap. Use direct socket address by default; do not trust arbitrary forwarded headers.
4. Define explicit configurable CORS allowlist with fail-closed production-like profile; preserve same-origin review path.
5. Add minimal security headers appropriate to API responses and frontend Nginx, with tests.
6. Preserve existing per-request account/assignment reload and server-derived roles/branch. Do not add refresh tokens, MFA, SSO, or a new auth architecture.
7. Assert denied requests produce zero mutations and zero success audit events.

**Gate:** Full HTTP security matrix passes against the intended runtime and existing role/branch tests remain green.

## Task 10 — OpenAPI source of truth and generated TypeScript client

**Objective:** Generate a deterministic contract and replace duplicated frontend transport shapes for demonstrated workflows.

**Files:**
- Modify: `backend/pom.xml` and narrow controller/DTO annotations
- Create: `api/openapi/medicore-v1.yaml`
- Create: `scripts/phase4/generate-openapi.sh`, `scripts/phase4/check-openapi-drift.sh`
- Modify: `frontend/package.json`
- Create generated client under `frontend/src/generated/api/`
- Modify shared frontend API adapter and representative feature callers/tests

**Steps:**
1. Write expected-path/schema/status tests before annotations/generation.
2. Generate OpenAPI from a safe isolated bootstrap; never require protected runtime credentials.
3. Normalize non-semantic generator timestamps/order to byte-stable output without deleting semantic metadata.
4. Generate a typed TypeScript client with exact request/response/error/auth contracts. Generated code is never hand-edited.
5. Adapt the existing shared API boundary, then demonstrated auth/patient/appointment/admission/dashboard/audit callers. Avoid a whole-UI rewrite.
6. Add transport tests for path, method, headers, serialization, `401/403/404/409/400`, and wire compatibility.
7. Generate twice and compare; drift check must fail on intentional stale copy and pass after regeneration.

**Gate:** Deterministic OpenAPI/client drift check, frontend type/test/build, and representative HTTP contract tests pass.

## Task 11 — Containerized synthetic journey and restart recovery

**Objective:** Prove the complete accepted workflow on the Compose/PostgreSQL stack, including restart and dependency failure.

**Files:**
- Modify: `frontend/playwright.config.ts` while preserving `trace: 'off'`
- Create/modify Phase 4 E2E under `frontend/e2e/`
- Create: `scripts/phase4/test-container-journey.sh`
- Create: `scripts/phase4/test-restart-recovery.sh`

**Steps:**
1. Generate process-local high-entropy synthetic credentials; do not log/persist them.
2. Start clean Compose stack and prove port ownership/health before browser tests.
3. Exercise login, acting context, patient, appointment, admission/bed, discharge, emergency, simulated invoice, command-center, and audit on desktop and mobile viewports.
4. Assert cross-branch rows never paint during context switch, not merely after effects settle.
5. Restart backend and prove migrations do not rerun and durable synthetic state remains.
6. Stop PostgreSQL, prove readiness failure/liveness truth, restore it, and prove recovery.
7. Keep traces/videos/screenshots off unless a failing local diagnostic requires scratch-only evidence; never use them as acceptance substitutes.

**Gate:** Full container journey and restart/dependency recovery pass with exact expected non-2xx counts.

## Task 12 — Canonical Phase 4 acceptance workflow

**Objective:** Make every required proof reproducible through one fail-fast entry point.

**Files:**
- Create: `scripts/phase4/acceptance.sh`
- Create: `.github/workflows/phase4-quality.yml` only if it uses no secrets and the repo already permits CI files
- Modify: `README.md`

**Steps:**
1. Compose serial gates: public-artifact scan → backend unit → PostgreSQL migration/integration/concurrency → frontend install/test/build → OpenAPI drift → image build → Compose health → backup/restore → security matrix → container journey → restart/dependency recovery → docs/traceability.
2. Each child command must propagate failure; no `|| true`, test suppression, silent H2 fallback, or echo-only proof.
3. Ensure cleanup traps target only Phase 4 disposable containers/databases/artifacts.
4. Run without Docker/PostgreSQL capability and prove explicit `BLOCKED/SKIPPED-SAFE` semantics do not claim PASS.
5. Run full canonical acceptance on the capable host.

**Gate:** One fresh end-to-end command exits 0 only when every required Phase 4 criterion passes.

## Task 13 — Documentation, traceability, and public-safety review

**Objective:** Make the engineering result reviewable without exaggerating readiness.

**Files:**
- Modify: `README.md`
- Modify: `docs/pdr.md` only by additive Phase 4 references; do not rewrite original intent
- Modify: `docs/implementation-status.md`
- Modify: `docs/traceability.md`
- Create/modify: `docs/plan4.md`, `docs/runbooks/*.md`, `docs/security/threat-model-phase4.md`
- Create: `docs/evidence/phase4-verification.md`

**Steps:**
1. Map FR-001..020 and SC-001..010 to exact source, tests, runtime commands, and measured output.
2. Document architecture, local review steps, migrations, rollback limitation (forward fix), backup/restore, observability, security, time semantics, API generation, failure recovery, and known limitations.
3. Keep claims bounded: Training/Portfolio, synthetic only, non-clinical, no compliance/SLA/real deployment.
4. Run tracked public-artifact scan for credentials, private topology, real personal data, and unsupported readiness claims; repair docs rather than weakening checks.
5. Ensure docs reference actual commands/files and current exact test totals—not historical guesses.

**Gate:** Every requirement has fresh evidence or Phase 4 is `BLOCKED`; no public-safety findings remain.

## Task 14 — Formal stop gate and worker report

**Objective:** End only with a reproducible terminal verdict and preserve the tree for owner review.

**Files:**
- Finalize local ignored: `artifacts/phase4/pi-execution-report.md`
- Finalize tracked: `docs/evidence/phase4-verification.md`

**Steps:**
1. Run `git diff --check` and inventory tracked + untracked changed paths.
2. Reject unrelated path changes, generated caches/build outputs, secrets, runtime env files, database dumps, and private endpoints.
3. Run `scripts/phase4/acceptance.sh` fresh.
4. Record exact commands, exit codes, test totals, image/stack identifiers (non-secret), backup/restore checksum result (checksum allowed; no credentials), known limits, and final `PASS | PARTIAL | BLOCKED`.
5. Do not commit, push, open PR, deploy, release, or merge. Leave the worktree intact.

**Final success definition:** All SC-001..010 pass with fresh evidence and no forbidden side effect. Otherwise report terminal failure honestly with first blocking gate and preserved partial work.
