# MediCore Phase 4 Completion and Repair Plan

**Status:** Approved completion plan from the independently verified T001–T050 checkpoint

**Current release level:** Training/Portfolio demo using synthetic data only

**Target:** Complete T051–T097 and produce reproducible operational-resilience evidence without claiming clinical, regulatory, or production readiness.

## 1. Verified starting point

The completion work starts from the following accepted evidence:

- PostgreSQL and Flyway migrations apply on an empty disposable database and validate under the PostgreSQL profile.
- Branch-local time conversion, typed workflow timestamps, precise invoice amounts, department scoping, and database concurrency constraints are covered by automated tests.
- Backend and frontend container definitions, Compose topology, disposable-target guards, and backup/restore scripts exist.
- The current independent baseline is 197 backend tests, 237 frontend tests, a successful frontend production build, and a 15-assertion backup/restore rehearsal.
- No real patient data, real credentials, external deployment, or production-readiness claim is part of this phase.

This evidence is a checkpoint, not final Phase 4 acceptance. Tasks T051–T097 remain open until every gate below is reproduced from a clean checkout.

## 2. Repair sequence

### Work package A — Observability and dependency recovery (T051–T057)

**Objective:** Prove that liveness describes process availability, readiness reflects database availability, recovery is automatic, and emitted telemetry is bounded and sanitized.

**Primary files:**

- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-postgres.yml`
- Modify/Create: `backend/src/main/resources/logback-spring.xml`
- Create/Modify: focused tests under `backend/src/test/java/com/mamtrex/hospital/observability/`
- Create: `docs/runbooks/observability.md`
- Create/Modify: scripts under `scripts/phase4/`

**Steps:**

1. Add a failing live probe test: healthy database means liveness/readiness are healthy; database loss keeps liveness healthy and makes readiness unhealthy; database restoration returns readiness to healthy without rebuilding the service.
2. Add only the Actuator and Prometheus dependencies required by the written contract.
3. Expose health, liveness, readiness, and Prometheus endpoints explicitly; keep all other Actuator endpoints denied.
4. Add structured JSON logging through the existing correlation-ID boundary.
5. Test required bounded fields and rejection/redaction of authorization tokens, passwords, request bodies, patient canaries, and unbounded resource identifiers.
6. Add low-cardinality request/business metrics and tests that reject patient, token, path-variable, body, and raw exception labels.
7. Run a disposable live database-loss/recovery rehearsal and document only commands that passed.

**Gate:** Focused observability tests, full backend tests, live database-loss/recovery rehearsal, and telemetry leakage tests all pass.

### Work package B — Security hardening (T058–T066)

**Objective:** Close authentication, authorization, CORS, proxy-address, and security-header gaps without changing the server-owned acting-assignment model.

**Primary files:**

- Modify: `backend/src/main/java/com/mamtrex/hospital/auth/SecurityConfig.java`
- Create/Modify: authentication rate-limit components under `backend/src/main/java/com/mamtrex/hospital/auth/`
- Modify: `frontend/nginx.conf`
- Create/Modify: focused security tests under `backend/src/test/java/com/mamtrex/hospital/auth/`
- Create: `docs/security/threat-model-phase4.md`

**Steps:**

1. Inventory every frontend write caller and raw/shared fetch path before changing global policy.
2. Add a request-level authorization matrix for anonymous, permitted, denied, forged-context, tampered-token, expired-token, disabled-user, and disabled-assignment cases.
3. Prove denied requests create no mutation and no success audit event.
4. Add a bounded, expiring login rate limiter keyed by direct socket address; do not trust forwarded addresses unless a separate trusted-proxy configuration is explicitly present.
5. Test generic login failures, threshold behavior, expiry, bounded-store eviction, and recovery after the window.
6. Add an explicit CORS allowlist with fail-closed behavior in the PostgreSQL/review profile.
7. Add compatible API and Nginx security headers without breaking same-origin `/api` routing.
8. Document the threat model and explicitly exclude refresh tokens, MFA, SSO, clinical certification, and real deployment.

**Gate:** Focused security matrix, full backend tests, frontend tests/build, and same-origin container access all pass.

### Work package C — Deterministic OpenAPI and typed client (T067–T075)

**Objective:** Generate a stable API contract and typed frontend client from the backend source, detect drift, and preserve the current shared transport boundary.

**Primary files:**

- Modify: `backend/pom.xml`
- Modify: representative controllers/DTOs only where contract metadata is missing
- Create: `api/openapi/medicore-v1.yaml`
- Create: `scripts/phase4/generate-openapi.sh`
- Create: `scripts/phase4/check-openapi-drift.sh`
- Create: `frontend/src/generated/api/`
- Modify: the existing shared frontend API boundary and representative callers
- Create/Modify: backend contract and frontend transport tests

**Steps:**

1. Write failing assertions for required paths, schemas, validation constraints, and `400/401/403/404/409` responses.
2. Add compatible Springdoc integration and only the annotations that cannot be derived safely.
3. Bootstrap contract generation with process-local synthetic configuration and a disposable PostgreSQL target.
4. Generate `medicore-v1.yaml` twice and require byte-for-byte stability.
5. Generate the TypeScript client deterministically; never hand-edit generated output.
6. Add transport tests for method, path, headers, body, and error/status contracts.
7. Adapt representative callers through the existing API boundary rather than performing a broad UI rewrite.
8. Prove stale generated artifacts fail the drift check and regenerated artifacts pass.

**Gate:** Backend contract tests, two-run deterministic generation, drift check, frontend tests/typecheck/build, and representative real HTTP contract tests all pass.

### Work package D — Full container and browser journey (T076–T084)

**Objective:** Exercise the demonstrated workflow through the same-origin container stack at desktop and mobile viewports, including restart and dependency recovery.

**Primary files:**

- Modify/Create: Playwright configuration and E2E tests under the repository's existing E2E boundary
- Modify/Create: `scripts/phase4/` runtime orchestration scripts
- Modify only when a test proves a defect: relevant frontend/backend source

**Steps:**

1. Keep Playwright traces, screenshots, and video disabled as acceptance substitutes; publish no stale browser artifact.
2. Generate synthetic credentials before Playwright configuration loads and retain them only in the child process.
3. Start a clean disposable stack and prove service/port ownership before browser execution.
4. Exercise desktop and mobile journeys: login, acting context, patient, appointment, admission/bed, discharge, emergency, simulated invoice, command center, and audit.
5. Assert one-line mobile navigation, overflow safety, and no stale-branch paint during context switching.
6. Restart the backend and prove zero pending migrations with preserved synthetic state.
7. Stop and restore PostgreSQL; verify the liveness/readiness split and recovery.
8. Assert every intentionally exercised non-2xx response exactly and reject unrelated console/page errors.

**Gate:** Desktop and mobile E2E journeys, context discrimination, restart, migration idempotency, and database-loss recovery all pass against a clean disposable stack.

### Work package E — Canonical acceptance and CI (T085–T089)

**Objective:** Replace scattered commands with one fail-fast acceptance entry point that cannot report success when Docker or PostgreSQL proof is unavailable.

**Primary files:**

- Create: `scripts/phase4/acceptance.sh`
- Modify/Create: supporting scripts under `scripts/phase4/`
- Create, if repository CI policy permits: `.github/workflows/phase4-quality.yml`

**Steps:**

1. Compose static scans, backend tests, PostgreSQL migrations/concurrency, frontend tests/build, image builds, Compose health, backup/restore, observability/security, OpenAPI drift, and E2E in dependency order.
2. Use fail-fast stage reporting and cleanup traps restricted to named Phase 4 disposable resources.
3. Add negative self-tests proving absent Docker/PostgreSQL cannot produce a false PASS.
4. Add a secret-free CI workflow only if the repository can provide the required container capabilities; otherwise document the local-only gate honestly.
5. Run the canonical acceptance script on a capable host and capture exact stage totals.

**Gate:** One fresh `scripts/phase4/acceptance.sh` run exits zero after every mandatory stage; intentional prerequisite-removal tests exit non-zero.

### Work package F — Evidence, documentation, and publication gate (T090–T097)

**Objective:** Align public claims with reproduced evidence and make the branch safe to review.

**Primary files:**

- Modify: `README.md`
- Modify: `docs/pdr.md`
- Modify: `docs/implementation-status.md`
- Modify: `docs/traceability.md`
- Create: `docs/evidence/phase4-verification.md`
- Modify/Create: Phase 4 runbooks

**Steps:**

1. Update documentation only from commands reproduced during Work packages A–E.
2. Map FR-001–FR-020 and SC-001–SC-010 to exact source, test, runtime, and documentation evidence.
3. Remove credentials, private topology, real personal data, generated logs/backups, and unsupported clinical, compliance, production-capacity, or SLA claims.
4. Audit tracked and untracked candidates; reject unrelated files and generated/runtime artifacts.
5. Run `git diff --check` and the canonical acceptance script from a clean checkout.
6. Record the final verdict as `PASS`, `PARTIAL`, or `BLOCKED` from evidence, never from task completion percentage.

**Gate:** Traceability is complete, staged/public-artifact scans are clean, canonical acceptance passes from a clean checkout, and the project still states its synthetic Training/Portfolio boundary.

## 3. Execution order and stop conditions

Execution is strictly serialized:

`A → B → C → D → E → F`

Stop and report `BLOCKED` rather than improvising when any of these occurs:

- a test requires a real credential, patient record, non-disposable database, or external deployment;
- a migration or restore target does not pass the disposable-target guard;
- a security or OpenAPI change requires a new product decision;
- a clean checkout cannot reproduce the claimed gate;
- a scanner detects a credential, private endpoint, generated runtime artifact, or unsupported readiness claim.

## 4. Final definition of done

Phase 4 is complete only when all conditions are true:

- T001–T097 are mapped to fresh evidence.
- Backend, frontend, PostgreSQL, migration, concurrency, backup/restore, observability, security, OpenAPI, container, desktop E2E, and mobile E2E gates pass.
- Liveness/readiness behavior is demonstrated during real disposable database loss and recovery.
- Generated OpenAPI and TypeScript client artifacts are deterministic and drift-free.
- `scripts/phase4/acceptance.sh` passes from a clean checkout and fails closed when required infrastructure is absent.
- Public documentation contains no secret/private/real-data finding and makes no clinical, regulatory, SLA, or production-readiness claim.
- Publication remains a reviewable feature branch/PR until an explicitly verified integration decision is made.
