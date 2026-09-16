# Phase 4 Verification Evidence — FR-001..020 and SC-001..010

Status: Training/Portfolio reference implementation. Synthetic data only. Non-clinical.
This file maps every Phase 4 requirement to its exact source, test, and runtime
evidence. The terminal canonical acceptance run is the freshness authority; its
exact stage verdicts, test totals, and runtime are recorded in
`docs/implementation-status.md` (Phase 4 section) and in the ignored local
execution log `artifacts/phase4/pi-execution-report.md`.

Canonical acceptance command (fail-fast; exit 0 only when every stage passes):

```bash
scripts/phase4/acceptance.sh
```

Stages, in order: capability-negative-proof, script-self-tests,
public-artifact-scan, backend-suite, frontend-tests-build, openapi-drift,
container-static, backup-restore, security-headers, observability-live,
container-journey, docs-traceability, disposable-cleanup-proof.
A missing tool or unreachable Docker daemon exits 2 (BLOCKED) — a capability
gap can never produce PASS (stage 0 proves this negatively on every run).

## Functional requirements

- **FR-001** (PostgreSQL profile: Flyway + validate, no `ddl-auto=update`):
  source `backend/src/main/resources/application-postgres.yml`;
  tests `PostgresProfileConfigTest`, `PostgresProfileContextIntegrationTest`
  (real Testcontainers PostgreSQL); runtime stage `backend-suite`.
- **FR-002** (versioned migrations from empty + rehearsal path):
  `backend/src/main/resources/db/migration/V1__baseline_schema.sql` …
  `V4__scope_and_concurrency_constraints.sql`;
  `scripts/phase4/migrate-disposable-postgres.sh` (guard-fail-closed);
  tests `FlywayPostgresIntegrationTest`; empty-DB and Phase 3 rehearsal
  path rehearsal recorded in `docs/runbooks/backup-restore.md`; runtime
  stages `backend-suite`, `backup-restore`, `container-journey` (fresh
  review database migrates on boot; restart asserts zero pending).
- **FR-003** (invariant verification without printing sensitive rows):
  `scripts/phase4/verify-database-invariants.sh` (allowlisted counts and
  booleans only), exercised by `scripts/phase4/test-backup-restore.sh`.
- **FR-004** (real PostgreSQL integration: scoped workflows + concurrency):
  `backend/src/test/java/com/mamtrex/hospital/infrastructure/PostgresConcurrencyIntegrationTest.java`
  (CyclicBarrier HTTP races: exactly one 2xx winner, one typed 409 loser,
  zero partial rows), `organization/DepartmentScopeApiTest.java`,
  `MultiBranchOperationsApiTest.java`; runtime stage `backend-suite`.
- **FR-005** (Dockerfiles + Compose health ordering + runtime-only secrets):
  `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`,
  `compose.yaml` (healthcheck gating postgres → backend → frontend; every
  credential `${VAR:?required}`); guard `scripts/phase4/check-container-static.sh`;
  runtime stages `container-static`, `container-journey`.
- **FR-006** (backup/restore fail-closed + archive/checksum/invariants):
  `scripts/phase4/backup-postgres.sh`, `restore-postgres.sh`,
  `test-backup-restore.sh` (15/15 assertions incl. seven negative refusals);
  runbook `docs/runbooks/backup-restore.md`; runtime stage `backup-restore`.
- **FR-007** (structured, correlation-aware, bounded, sanitized logs):
  `backend/src/main/resources/logback-spring.xml`;
  tests `observability/StructuredLogSanitizationIntegrationTest.java`,
  `observability/ObservabilityConfigContractTest.java`; runtime stage
  `observability-live` (live JSON log lines and canary checks).
- **FR-008** (liveness/readiness split; bounded-cardinality metrics):
  tests `observability/LivenessReadinessDbLossIntegrationTest.java` (real
  `docker stop`/`start` of the database, same application process),
  `observability/MetricsBoundaryIntegrationTest.java`,
  `auth/ActuatorHealthProbeTest.java`; runtime stage `observability-live`.
- **FR-009** (bounded login rate limiting, tested recovery, generic failures):
  `auth/LoginRateLimiterTest.java`, `auth/LoginRateLimitIntegrationTest.java`.
- **FR-010** (CORS, security headers, actuator exposure, route policy):
  tests `auth/CorsPolicyTest.java`, `auth/SecurityHeadersTest.java`,
  `auth/ActuatorHealthProbeTest.java`,
  `auth/HttpSecurityBoundaryMatrixTest.java`; runtime stage
  `security-headers` (`scripts/phase4/check-security-headers.sh`: static
  nginx.conf set + live same-origin probes inside the real frontend image).
- **FR-011** (acting-context authorization; cross-branch non-disclosure):
  tests `auth/SecurityAuthorizationTest.java`,
  `organization/MultiBranchOperationsApiTest.java`; E2E
  `e2e/context-discrimination.spec.js` (no stale branch rows).
- **FR-012** (validated IANA branch zones; acting-branch time semantics;
  documented DST behavior):
  migration `V3__branch_time_zone_and_typed_workflow_values.sql`;
  tests `organization/BranchTimeServiceTest.java`,
  `organization/BranchZoneTypedValuesApiTest.java`; documented in
  `docs/pdr.md` (Phase 4 addendum) and `docs/traceability.md`.
- **FR-013** (department reads acting-context scoped; legacy rows hidden):
  test `organization/DepartmentScopeApiTest.java` (real PostgreSQL).
- **FR-014** (MRN/invoice uniqueness scope decided and enforced consistently):
  schema `V1__baseline_schema.sql` global unique constraints (Phase 4
  default: global preserved), service validation, tests
  (`patient/PatientJourneyApiTest.java`, `operations/CareOperationsApiTest.java`),
  decision documented in `docs/pdr.md` Phase 4 addendum and
  `docs/traceability.md`.
- **FR-015** (legacy String time/money → typed columns, wire-compatible):
  migration `V3__branch_time_zone_and_typed_workflow_values.sql`;
  tests `organization/BranchZoneTypedValuesApiTest.java` (wire-format
  compatibility asserted), `organization/BranchTimeServiceTest.java`.
- **FR-016** (OpenAPI generated from backend; deterministic drift check):
  `scripts/phase4/generate-openapi.sh`, `check-openapi-drift.sh`,
  tracked artifact `api/openapi/medicore-v1.yaml`;
  test `contract/OpenApiContractTest.java`; runtime stage `openapi-drift`
  (byte-exact regeneration comparison of YAML and generated client).
- **FR-017** (frontend transport uses generated typed contracts):
  `frontend/src/generated/api/` (read-only generated output),
  `frontend/src/test-utils/generatedApiTransport.test.js` (method/path/
  header/body and 400/401/403/404/409 contract tests); adopted callers in
  `frontend/src/features/**`; runtime stage `frontend-tests-build`.
- **FR-018** (canonical acceptance covers every listed area):
  `scripts/phase4/acceptance.sh` — the 13 serial stages of this file's
  header, each a real child command whose exit status is the verdict.
- **FR-019** (synthetic, public-safe fixtures and evidence):
  scanner `scripts/phase4/check-public-artifacts.sh` (self-tested by
  `test-check-public-artifacts.sh`); runtime stage `public-artifact-scan`;
  seed data is the documented synthetic organization/branches only.
- **FR-020** (no commit/push/deploy/release/real data/readiness claims):
  process evidence: the terminal run leaves the worktree intact
  (`git status` recorded in `artifacts/phase4/pi-execution-report.md`);
  docs carry only Training/Portfolio, synthetic, non-clinical wording;
  `public-artifact-scan` enforces the claim boundary on tracked files.

## Success criteria

- **SC-001** (empty + rehearsal migrations; second startup applies zero):
  `FlywayPostgresIntegrationTest`; `container-journey` restart step
  (`assert-no-pending-migrations` via `scripts/phase4/container-e2e-stack.sh`).
- **SC-002** (existing suites green or superseded with exact new totals):
  `backend-suite` (full `mvn test`) and `frontend-tests-build`
  (vitest + typecheck + build); exact fresh totals recorded in
  `docs/implementation-status.md` from the terminal run.
- **SC-003** (exactly one legal winner, no partial writes):
  `infrastructure/PostgresConcurrencyIntegrationTest.java`.
- **SC-004** (matching invariants + verified checksum):
  `test-backup-restore.sh` positive path (archive checksum verified;
  source-vs-restored allowlisted invariants identical).
- **SC-005** (Compose healthy from clean state; full synthetic journey):
  `container-journey` (`container-e2e-stack.sh run`: build → health →
  ownership proof → `e2e/journey.spec.js` desktop + mobile).
- **SC-006** (truthful liveness/readiness split; recovery after return):
  `LivenessReadinessDbLossIntegrationTest`; runtime stage
  `observability-live`; E2E `journey.spec.js` PostgreSQL stop/restore leg.
- **SC-007** (security matrix; zero unauthorized mutations/audit-success):
  `auth/HttpSecurityBoundaryMatrixTest.java`, `SecurityAuthorizationTest.java`;
  E2E exact non-2xx matrix (`e2e/journey.spec.js` T084 leg).
- **SC-008** (deterministic OpenAPI; drift green; client tests pass):
  `openapi-drift` stage + `generatedApiTransport.test.js`.
- **SC-009** (no credentials/private topology/real data/unsupported claims):
  `public-artifact-scan` stage + `git diff --check` in `docs-traceability`.
- **SC-010** (formal traceability maps every FR/SC to fresh evidence; stop gate):
  this file + `docs/traceability.md` Phase 4 section + terminal verdict in
  `docs/implementation-status.md`; stop gate = owner-review handoff, no
  release action (FR-020).

## Changed-path and side-effect discipline

- Changed paths for T085-T097 stay inside the packet allowlist:
  `scripts/phase4/`, `README.md`, `docs/`, `artifacts/phase4/pi-execution-report.md`
  (ignored), `specs/004-production-like-resilience/tasks.md`.
- No CI workflow was added: no `.github/` directory existed at baseline, so
  no demonstrated repository CI policy exists; acceptance is documented as
  local-only (T088) rather than fabricating CI.
- Disposable cleanup: every container/database/volume used by the run is
  process-owned and removed by its owning script's trap; the final
  `disposable-cleanup-proof` stage verifies none remain. No unrelated host
  container, service, or port is ever touched.
