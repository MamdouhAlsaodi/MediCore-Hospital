# Phase 4 Contract Baseline (Phase 5 T007 inventory)

**Status:** Source-grounded inventory only — read from this worktree at baseline
`0c5a303a72b31e15cdac0d41773580a3585605e1`. No contract change is proposed here.
Phase 5 extends these contracts compatibly (v1, no break) per the locked clarifications.

## Auth surface (backend `auth` package)

- `POST /api/auth/login` — `AuthController.login`; body `LoginRequest(username, password)`
  (both `@NotBlank`). Success = `ActingContextService.Session`; wrong password, disabled
  account, and no-valid-assignment share one non-enumerating 401; rate-limited addresses get
  one generic 429 with `Retry-After` (`LoginRateLimiter`, keyed by direct socket address,
  no forwarded headers). Rate-limit refusals/failures create no audit events.
- `POST /api/auth/context` — `switchContext`; body `ContextSwitchRequest(assignmentId @NotNull,
  branchId optional)`. Refusals (unknown/disabled/out-of-scope target, or branch outside the
  organization for `BRANCH` scope) share one controlled 403 in the shared `ApiError` shape.
- `SecurityConfig` — stateless (`SessionCreationPolicy.STATELESS`), CSRF disabled (token
  auth), `/actuator/health*` and `POST /api/auth/login` permitAll; `/api/auth/**` authenticated;
  `/api/audit/**` ADMIN-only; role-scoped matchers for staff/departments/shifts, invoices/
  insurance-claims, lab/radiology, drugs, inventory, patients/appointments/admissions writes;
  `/api/**` fallback ADMIN. Any Phase 5 route must be added inside this fail-closed structure.

## JWT structural claims (`JwtService`)

- Issued claims: `sub` (username), `assignmentId` (UUID string), `role` (**display/diagnostics
  only — never trusted for authority**), `scope` (`ORGANIZATION|BRANCH|DEPARTMENT`),
  `organizationId`, `branchId` (always present today: every scope resolves to a concrete
  active branch), optional `departmentId` (present only for `DEPARTMENT` scope). HMAC signing;
  TTL default 60 min (`hospital.jwt.ttl-minutes`).
- `parseStructural` → immutable `StructuralClaims(subject, assignmentId, scope, organizationId,
  branchId, departmentId)`; missing/malformed required claims throw through the fail-closed
  anonymous boundary; `departmentId` null semantics are enforced by the reload comparison.
- `JwtFilter` re-derives exactly one authority from the server assignment row on every request
  via `ActingContextService.reload` — token claims only point at the row; **no Phase 5
  `hospitalId` claim exists yet (T025 adds it).**

## Acting context and session reload (`ActingContext`, `ActingContextService`)

- `ActingContext(username, assignmentId, role, scope, organizationId, branchId, departmentId)`
  — immutable `Principal` allowlist; no password/token/entity. **`branchId` is always concrete;
  Phase 5 T023 makes a non-null `hospitalId` part of this record.**
- `Session(accessToken, tokenType, username, roles, List<AssignmentView>, ActingContextView)`;
  `AssignmentView(id, role, scope, organizationId, organizationLabel, branchId, branchLabel,
  departmentId, departmentLabel, enabled)`; `ActingContextView(username, assignmentId, role,
  scope, organizationId, branchId, departmentId)` — strict allowlists; assignments list only
  currently valid enabled rows that pass current-state invariants.
- `reload(StructuralClaims)` — rebuilds server state and requires every structural field to
  match exactly (including department null semantics; role never compared); any stale/tampered
  claim yields empty → request falls to anonymous → fail closed. Login/switch audit events
  record no token/password/body/role-union details.
- Scope semantics: `ORGANIZATION` acts across the organization with a selected branch;
  `BRANCH` acts on its fixed branch; `DEPARTMENT` derives its active branch.
  `AssignmentScope` values today: `ORGANIZATION, BRANCH, DEPARTMENT` — **Phase 5 T019 adds
  `HOSPITAL` while preserving wire compatibility.**

## Tracked OpenAPI contract (`api/openapi/medicore-v1.yaml`, 1547 lines)

Generated from backend springdoc annotations (`OpenApiConfig`); verified byte-exact by
`scripts/phase4/check-openapi-drift.sh`; frontend consumes `frontend/src/generated/api/`
(`index.ts`, `operations.ts`, `schemas.ts`) — hand-maintained duplicated shapes are forbidden
(constitution VI). Current paths:

- `/api/auth/login`, `/api/auth/context`
- `/api/patients`, `/api/patients/{id}`
- `/api/appointments`, `/api/appointments/{id}`
- `/api/admissions`, `/api/admissions/{id}`, `/api/admissions/{id}/status`, `/api/admissions/{id}/bed`
- `/api/dashboard` (summary), `/api/dashboard/network`, `/api/dashboard/branch`
- `/api/audit`

**No `/api/network/hierarchy`, capacity, or transfer paths exist at baseline** — Phase 5 adds
them (T035, T109, T097) with regenerated client and drift proof.

## Frontend context contract (`frontend/src/auth.js`, 125 lines)

- Session/assignment/context parsers validate the same allowlist shapes server-side
  (`assignmentId`, `scope`, `organizationId`, `branchId`, optional `departmentId`; string
  equality checks across context/assignment).
- Context key: `assignmentId:branchId` (`'no-acting-context'` when either is missing) —
  screens re-render when the key changes to avoid stale cross-branch paint.
  **Phase 5 T059 extends the key with assignment/hospital/branch/department; T055 extends the
  parsers with hospital id.**

## Error/observability contracts

- Shared `ApiError` shape for controlled failures; `GlobalExceptionHandler` maps
  `NotFoundException` → 404, `AccessDeniedException` → 403, `DuplicateKeyException`,
  `InvalidStateTransitionException`, `InvalidParameterValueException`; generic security
  failures stay non-enumerating. Cross-scope ids resolve to 404, indistinguishable from
  nonexistent (non-enumeration policy Phase 5 must keep for foreign hospitals).
- Structured JSON logs (`StructuredJsonEncoder`, `RequestObservationLogFilter`,
  `CorrelationIdFilter`) with bounded fields — no credentials/tokens/bodies/sensitive
  personal fields; metrics cardinality bounded (`MetricsBoundaryConfig`).

## Compatibility conclusions for Phase 5

1. Wire value `ORGANIZATION` stays valid (displayed as "Network"); `HOSPITAL` is additive.
2. Every authorized request remains bound to one concrete branch; adding a concrete hospital
   id tightens (never loosens) this rule.
3. All response records are strict allowlists — hospital fields are additive optional→required
   evolutions gated by contract tests and regenerated client.
4. OpenAPI-first: backend annotations/contract tests before UI adoption; deterministic drift
   check must stay green after regeneration.
