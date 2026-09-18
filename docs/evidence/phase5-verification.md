# Phase 5 US1 Verification Evidence — Authorized Network Hierarchy (T031–T045)

Status: Training/Portfolio reference implementation. Synthetic data only. Non-clinical.
This file records the US1 (multi-hospital hierarchy) evidence for packet
`MEDICORE-PHASE5-US1-NETWORK-HIERARCHY-007`: the exact authorization and
non-enumeration matrix, the deterministic ordering contract, the bounded
query-count proof, and the fresh commands/totals that back them. All fixtures
are unmistakably synthetic (`NET-*`, `DEMO-*`, `synthetic.test`); no real
hospital, person, credential, private topology, or protected runtime value
appears anywhere in this phase.

## 1. Contract surface (exactly one new path)

- Source: `backend/src/main/java/com/mamtrex/hospital/organization/`
  `NetworkHierarchyDtos.java` (strict immutable records),
  `NetworkHierarchyService.java` (server-derived slice), and
  `NetworkHierarchyController.java` (one mapping, T035/T040).
- Endpoint: `GET /api/network/hierarchy` — no path or query parameters.
  Client-supplied hierarchy identifiers are inexpressible, so no client or
  foreign id can ever widen the response.
- Response allowlist (planning contract parity):
  `NetworkHierarchy { organizationId, organizationCode, organizationName,
  hospitals[] }`, `HospitalView { id, code, name, regionLabel, timeZone,
  active, branches[] }`, `BranchView { id, hospitalId, code, name, timeZone,
  active }`. The DTO serialization shape is pinned key-by-key (including
  declaration order, always-serialized false/empty values, defensive list
  copies, byte-identical re-serialization, and zero persistence metadata) by
  `NetworkHierarchyDtosTest` (T031).
- Route authorization (T036): `GET /api/network/hierarchy` is
  `authenticated()` in `SecurityConfig`, ordered ahead of the ADMIN-only
  `/api/**` catch-all — every acting scope can read exactly its slice.
- Contract artifacts (T041): `api/openapi/medicore-v1.yaml` and
  `frontend/src/generated/api/` were regenerated only through
  `scripts/phase4/generate-openapi.sh` (which drives
  `scripts/phase4/generate-openapi-client.mjs`); the diff is exactly one new
  path (`/api/network/hierarchy`, operationId `getAuthorizedNetworkHierarchy`)
  and the three new schemas. `scripts/phase4/check-openapi-drift.sh` exits
  0: "no drift — tracked artifacts match the served contract".
- One additional configuration seam was genuinely required for T041:
  `backend/src/main/resources/application.yml` adds
  `com.mamtrex.hospital.organization` to `springdoc.packages-to-scan` and an
  explicit `springdoc.paths-to-match` allowlist. Without it the documented
  contract cannot include the annotated endpoint; with the allowlist the
  organization package's legacy ADMIN routes stay undocumented, so the
  contract gains exactly the one new path and nothing else.

## 2. Authorization / non-enumeration matrix (T032, real HTTP, H2)

Fixture (`NetworkHierarchyApiTest`): one synthetic network `NETHIER-ORG-001`
with three hospitals (`NET-HOSP-001` UTC, `NET-HOSP-002` America/New_York,
`NET-HOSP-003` Asia/Tokyo), two active branches per hospital, one department
on `NET-BR-101`, and five accounts bound by scope.

| Actor (scope) | Request | Result | Exactly visible |
|---|---|---|---|
| network ADMIN (`ORGANIZATION`) | `GET /api/network/hierarchy` | `200` | all 3 active hospitals, 2 branches each |
| hospital ADMIN (`HOSPITAL`, `NET-HOSP-002`) | same | `200` | only `NET-HOSP-002` + its 2 branches |
| branch NURSE (`BRANCH`, `NET-BR-302`) | same | `200` | only `NET-HOSP-003` with only `NET-BR-302` |
| department DOCTOR (`DEPARTMENT`, `NET-DEP-101`) | same | `200` | only `NET-HOSP-001` with only `NET-BR-101` |
| any caller appending `?hospitalId=<foreign>&branchId=<foreign>` | same | `200` | body byte-identical to the plain call — parameters are not accepted for expansion |
| hospital ADMIN of a deactivated hospital | same | `401` | context reload fails closed immediately (FR-004) |
| branch NURSE whose hospital was deactivated (branch still active) | same | `403` | one generic body `{"error":"access denied"}` — hospital existence is not disclosed |
| branch NURSE whose branch was deactivated | same | `401` | immediate unauthenticated boundary |
| anonymous caller | same | `401` | shared non-enumerating boundary |
| account with no acting assignment | login | `401` | can never obtain a hierarchy response |

Non-enumeration proof: the hospital-scoped body is asserted to contain zero
foreign hospital/branch identifiers (all foreign ids collected from the
repository and intersected with every id-like value in the response body).

Inactive-ancestor network-view proof (FR-004): after deactivating
`NET-HOSP-003`, the network view shows exactly `[NET-HOSP-001, NET-HOSP-002]`
(inactive hospital and all of its branches disappear); after deactivating
`NET-BR-302`, `NET-HOSP-003` shows only `[NET-BR-301]`.

## 3. Deterministic ordering

- Hospitals appear in ascending `code` order; branches appear in ascending
  `code` order inside each hospital (`NetworkHierarchyService` keeps the
  repositories' ordered reads; no client-side re-sorting).
- Pinned by `networkScopeReturnsEveryActiveHospitalAndBranchInDeterministicOrder`
  (`[NET-HOSP-001, NET-HOSP-002, NET-HOSP-003]`, each with its ordered branch
  list) and by the demo-fixture assertions in `DemoDataInitializerTest`
  (network dashboard branch order `[DEMO-BR-001, DEMO-BR-002, DEMO-BR-003,
  DEMO-BR-1101, DEMO-BR-1102, DEMO-BR-2201, DEMO-BR-2202]`).

## 4. Bounded query count (T037)

`hierarchyQueryCountStaysBoundedAsTheAuthorizedFixtureGrows` measures real
Hibernate statistics (`hibernate.generate_statistics=true`) around the HTTP
call: the query count for the 3-hospital fixture must EQUAL the count
measured after the fixture grows to 6 hospitals (12 branches), and both must
stay under a fixed absolute bound (32). A per-card or per-hospital loader
(N+1) would scale with the fixture and break the equality. The service loads
the authorized slice with a fixed number of repository queries (one
organization read, one hospital-set read, one branch-set read) and groups
branches by the lazy proxy's foreign-key identifier via
`PersistenceUnitUtil.getIdentifier`, which never initializes the proxy —
so the count is independent of hospital/branch cardinality.

## 5. Synthetic fixture (T038/T039)

`DemoDataInitializer` (opt-in, `medicore.demo.seed=true`) now provisions
three deterministic hospitals — `LEGACY-HOSPITAL-001` (UTC, owning the
unchanged Phase 4 cohort) plus `DEMO-HOSP-002` "Demo Riverside Hospital"
(America/New_York) and `DEMO-HOSP-003` "Demo Harborview Hospital"
(Asia/Tokyo) — with two bare synthetic branches each
(`DEMO-BR-1101/1102`, `DEMO-BR-2201/2202`). The fixture spans three IANA
zones (exceeds the two-zone minimum), keeps `DEMO-BR-001` as the
deterministic first-by-code branch (review-account provisioning is
unchanged), and is restart-idempotent: `phase5FixtureSpansSeveralZonesAndIsDeterministicAcrossRestart`
proves two re-seed runs insert zero hospitals/branches/audit events and leave
every business key and identity untouched, and the audit ledger is pinned
exactly (61 events = 59 CREATE + 2 bed-assignment UPDATE).

## 6. Fresh commands and totals (the freshness authority)

JDK: Temurin 21.0.12 (`/home/server/.cache/yui/jdk-21-temurin/bin` first on
PATH); Maven runs through the containerized toolchain
(`scripts/phase4/maven-toolchain.sh`).

| Gate | Command | Result |
|---|---|---|
| RED (T031/T032) | `mvn test-compile` (toolchain) | compilation errors: `NetworkHierarchyDtos`/controller missing |
| RED (T039) | `mvn test -Dtest=DemoDataInitializerTest` | `Tests run: 14, Failures: 7` — exactly the new fixture expectations |
| RED (T042) | `npx vitest run src/test-utils/generatedApiTransport.test.js` | import failure: `features/network/networkApi.js` missing |
| GREEN focused (T044) | `mvn test -Dtest='NetworkHierarchyDtosTest,NetworkHierarchyApiTest,DemoDataInitializerTest'` | `Tests run: 31, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS |
| Full backend (T044) | `mvn test` (toolchain) | `Tests run: 278, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS |
| OpenAPI drift (T041/T044) | `bash scripts/phase4/check-openapi-drift.sh` | exit 0 — "no drift — tracked artifacts match the served contract" |
| Frontend tests (T044) | `npm test` (frontend) | `Test Files 16 passed (16)`, `Tests 259 passed (259)` |
| Typecheck (T044) | `npm run typecheck` (frontend) | clean (strict TS over the regenerated client) |
| Production build (T044) | `npm run build` (frontend) | `✓ built in 8.50s` |
| Artifact scan | `git diff --check`; `bash scripts/phase5/check-public-artifacts.sh` | clean |

Backend total grew exactly 260 → 278 (+18 = 8 DTO + 9 API + 1 fixture test);
no Phase 4 test was weakened.

## 7. Scope boundary

No SaaS/multi-tenant expansion, no clinical/compliance claim, no
microservices, no client-expanded scope, and no UI/page/selector work
(reserved for T056+). T046 and later remain unchecked and unstarted.

---

# Phase 5 US2 Backend Evidence — Hierarchical Acting Authority (T046–T054)

Status: Training/Portfolio reference implementation. Synthetic data only. Non-clinical.
This section records the US2 backend/contract half (packet
`MEDICORE-PHASE5-US2-AUTHORITY-BACKEND-009`). All fixtures are unmistakably
synthetic (`HIACT-*`, `AUTHZ-*`, disposable test passwords); no real hospital,
person, credential, private topology, or protected runtime value appears here.

## 1. What each task delivered

- **T046 (RED first)** — new `HierarchicalActingContextApiTest` (isolated H2,
  real HTTP): login/session/context responses for all four assignment shapes
  carry the strict allowlist including `hospitalId` (`AssignmentView` also
  `hospitalLabel`); ORGANIZATION login binds the deterministic first usable
  branch's own facility; HOSPITAL login binds its facility plus its
  deterministic first active branch; BRANCH/DEPARTMENT logins derive the
  hospital through the server chain; a four-shape account resolves at login in
  the deterministic (role, scope, id) order; an ORGANIZATION switch to an
  allowed hospital/branch pair binds all three ids and one role; a HOSPITAL
  switch stays inside its fixed facility.
- **T047 (RED first)** — same file: switch refusals for mismatched
  (hospital≠branch facility), swapped-mismatch, unknown hospital, and unknown
  branch are 403 with one byte-identical generic body (timestamp aside);
  missing selection targets refuse generically; a branch-id-only ORGANIZATION
  switch binds the server-derived facility (FR-007); inactive-hospital
  branches are skipped at login and a hospital-scope login on an inactive
  facility gets the non-enumerating 401; a tampered `hospitalId` claim on an
  otherwise-valid token is 401 while the re-signed control authenticates
  (T052 pin).
- **T048** — `BranchAccessService` is now the full-hierarchy seam: a branch is
  usable only when it is active, its hospital facility exists and is active,
  and that facility belongs to the assignment's organization
  (`requireActiveBranchInOrganization`, `requireActiveBranchInHospital`);
  `deterministicActiveBranch` skips inactive-hospital branches; fixed
  BRANCH/DEPARTMENT scopes must match any supplied hospital id
  (`requireSameHospital`). Reload-safe: the per-request path runs detached
  (OSIV off), so lazy proxies contribute only identifiers; state is read via
  repositories.
- **T049** — login resolution keeps the repository's deterministic
  (role, scope, id) order across all four shapes and resolves ORGANIZATION and
  HOSPITAL scopes to concrete hospital/branch chains.
- **T050** — `switchContext` requires the target branch for both selection
  scopes (ORGANIZATION, HOSPITAL), derives the acting hospital from the
  selected branch's facility, and refuses any supplied hospital id that does
  not match the server chain; fixed scopes accept only matching
  hospital/branch ids. Every target is revalidated against the full active
  ancestor chain before a context is issued.
- **T051** — `ContextSwitchRequest` carries `hospitalId`; `AssignmentView`
  gains `hospitalId`/`hospitalLabel`; `ActingContextView` gains `hospitalId`.
  Strict allowlists — no entities, no persistence metadata.
- **T052** — per-request structural equality includes `hospitalId`
  (`ActingContextService.matchesStructuralClaims`), pinned RED→GREEN by the
  tampered-hospital-claim test; stale pre-hierarchy tokens stay 401
  (existing T024 pin re-verified in the full suite).
- **T053** — `SecurityAuthorizationTest` adds the immediate-invalidation
  matrix: deactivating a hospital 401s BRANCH/HOSPITAL/DEPARTMENT/ORGANIZATION
  tokens on their next request even with active branch rows, and every
  re-login gets the non-enumerating credentials body; a BRANCH-scope token
  dies immediately when its own fixed branch is deactivated. Disabled- and
  deleted-assignment invalidation were already pinned and re-verified.
- **T054** — `api/openapi/medicore-v1.yaml` and `frontend/src/generated/api/`
  regenerated only via `scripts/phase4/generate-openapi.sh`; contract delta is
  exactly the hospital fields plus the extended 403 description;
  `scripts/phase4/check-openapi-drift.sh` exits 0 ("no drift — tracked
  artifacts match the served contract").

## 2. Fresh commands and totals (freshness authority)

JDK: Temurin 21.0.12 (`/home/server/.cache/yui/jdk-21-temurin/bin` first on
PATH); Maven via `scripts/phase4/maven-toolchain.sh`.

| Gate | Command | Result |
|---|---|---|
| RED | `mvn test -Dtest=HierarchicalActingContextApiTest` | `Tests run: 11, Failures: 10` — exactly the new hospital expectations |
| GREEN focused | `mvn test -Dtest='HierarchicalActingContextApiTest,SecurityAuthorizationTest'` | `Tests run: 52, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS |
| Full backend | `mvn test` | `Tests run: 291, Failures: 2, Errors: 0, Skipped: 0` (278 baseline + 11 + 2; the 2 are the out-of-scope pins below) |
| Contract | `scripts/phase4/generate-openapi.sh` then `scripts/phase4/check-openapi-drift.sh` | exit 0 — deterministic, no drift |
| Typecheck | `npm run typecheck` (frontend, strict) | clean |

## 3. Out-of-scope test pins that legitimately fail (PARTIAL reason)

1. `MultiBranchOperationsApiTest.loginResponseCarriesTheActingAssignmentAllowlistWithOneSelectedRole:798`
   pins the pre-hospital acting-context allowlist; T051 intentionally adds
   `hospitalId` (same migration already applied in-scope to
   `SecurityAuthorizationTest`'s pins). One-line key-set update.
2. `NetworkHierarchyApiTest.inactiveHospitalFailsClosedForEveryScopeWithoutDisclosure:462`
   pins a BRANCH actor under an inactive hospital receiving 403 from the
   hierarchy service. T048/T053/FR-004 invalidate the acting context itself:
   the next request is unauthenticated (401) — exactly the behavior the
   sibling hospital-scope assertion in the same test already expects and
   spec US2 scenario 2 permits ("unauthenticated or refused immediately").
   Per SC-011 this is an intentional, equal/stronger migration of one
   assertion.

Both files are outside this packet's allowed paths, so they were not edited;
the packet therefore closes PARTIAL with these two named migrations for owner
authorization.

## 4. Scope, security, and environment audit

- `git diff --check`: clean. `scripts/phase5/check-public-artifacts.sh`: clean, exit 0.
- Session-changed paths are exactly the packet allowlist (5 auth files, the new
  test, generated contract artifacts, evidence/tasks/reports). `BranchRepository`
  was touched and byte-reverted in-session; no residual delta.
- Protected env (`/home/server/.config/medicore/runtime.env`,
  `/home/server/medicore-runtime/trial.env`) never read; only H2/Testcontainers
  disposable stores used.
- No dependency install, no frontend auth/UI changes (generated client only),
  no commit/push/PR/merge/deploy/release. T055 remains unchecked and unstarted.

---

# Phase 5 US2 Completion Evidence — Hospital-Aware Frontend Context (T055–T061)

Status: Training/Portfolio reference implementation. Synthetic data only. Non-clinical.
This section records the completion of US2 (packet
`MEDICORE-PHASE5-US2-COMPLETE-010`): the two owner-authorized inherited
contract-pin migrations plus the full US2 frontend half. All fixtures are
synthetic (`NET-*`, `Demo *`, disposable test tokens); no real hospital,
person, credential, private topology, or protected runtime value appears here.

## 1. Inherited contract-pin migrations (owner-authorized by this packet)

1. `MultiBranchOperationsApiTest.loginResponseCarriesTheActingAssignmentAllowlistWithOneSelectedRole`
   — the acting-context allowlist gains exactly `hospitalId` (T051's
   documented contract), with a new non-null binding assertion. The
   top-level login allowlist is unchanged.
2. `NetworkHierarchyApiTest.inactiveHospitalFailsClosedForEveryScopeWithoutDisclosure`
   — a BRANCH actor under an inactive hospital now expects `401`
   (unauthenticated immediately) instead of the superseded service-level
   `403`, matching its hospital-scope sibling and spec US2 scenario 2
   ("unauthenticated or refused immediately"); the network-view disappearance
   and non-disclosure assertions are unchanged. SC-011 intentional,
   equal/stronger migration.

## 2. What each task delivered

- **T055** — `frontend/src/auth.js`: assignment views validate optional
  `hospitalId`/`hospitalLabel`; acting contexts validate optional
  `hospitalId`; a fixed-scope (HOSPITAL/BRANCH/DEPARTMENT) context that names
  a different hospital than its assignment fails the reload closed. New
  `actingContextIdentity(session)` resolves the complete identity, deriving
  the hospital from the acting context when present and through the
  fixed-scope assignment otherwise (the frozen browser login parser
  `parseActingSessionPayload` stores the context without the hospital field;
  `frontend/src/api.js` is outside this packet's allowed paths and its
  normalized output is pinned by `multiBranchContracts.test.js`, so the
  fallback is the only seam-compatible resolution — no client-side lookup
  ever invents a hospital). RED first: 5 new tests in
  `frontend/src/authorization.test.js` failed against the pre-hospital
  parsers (`Tests 5 failed | 15 passed`).
- **T056** — RED first: `NetworkContextSelector.test.jsx` failed at import
  (module absent), then pinned the derivation contract: ORGANIZATION
  assignments offer exactly the server-issued active hospital/branch pairs of
  the authorized slice; HOSPITAL assignments are constrained to their fixed
  facility (a smuggled foreign branch row never leaks in); BRANCH/DEPARTMENT
  assignments offer exactly their own server-issued pair without needing the
  hierarchy; inactive hospitals/branches and empty slices yield nothing;
  current-target recognition goes by the server-bound (assignment, branch)
  pair.
- **T057** — `NetworkContextSelector.jsx`: the hierarchy-aware selector.
  Targets carry (assignment, hospital, branch) triples keyed by all three
  ids; the switch submits all three to `POST /api/auth/context`
  (`actingContextApi.switchContext` extended with `hospitalId`, still
  omitting absent ids so the frozen transport pins stay green). No free-form
  or client-expanded ids are expressible. Accessibility contract carried over
  and extended: labeled native select, busy/loading statuses (role="status"),
  in-place role="alert" denials (403 keeps the prior context; 401 delegates
  to the shell), loading entries while the slice is in flight, honest
  unavailable entry, single-target help line.
- **T058** — `AppShell.jsx`: the selector is now
  `NetworkContextSelector`, fed by `GET /api/network/hierarchy` (via the US1
  `fetchNetworkHierarchy` adapter) instead of the ADMIN-only
  `GET /api/organization`; the whoami names the full server-issued chain —
  organization — hospital — branch for ORGANIZATION contexts, hospital —
  branch for HOSPITAL contexts (resolved from the slice), hospital — branch —
  department labels for fixed scopes — with the same neutral
  loading/id-fallback discipline. Navigation, focus management (heading
  focus, lost-focus recovery), screen remounting, responsive layout, and all
  screens are untouched and stay green.
- **T059** — `actingContextKey` is now the complete
  `assignmentId:hospitalId:branchId:departmentId` identity (neutral
  `no-acting-context` fallback). Every context-bound screen (dashboard,
  patients, appointments, admissions, emergency visits, invoices, beds) tags
  and gates its loaded data through this shared seam, so data loaded under
  one hospital context can never be reused under another — including keys
  that differ only in the hospital segment (pinned in
  `DashboardPage.test.jsx`).
- **T060** — render-phase discrimination tests. `AppShell.test.jsx`: the
  hierarchy state is tagged with the acting-context key it loaded under and
  gated at render phase; a hierarchy response resolved under a switched-away
  context and resolved late never paints (asserted before and after the new
  context's own read resolves; the effect-cleanup flag is not relied upon).
  `DashboardPage.test.jsx`: the same decisive late-response matrix for the
  network comparison section, plus the pure-gate pin that hospital-differing
  keys never share data.
- **T061** — gates below.

## 3. Fresh commands and totals (freshness authority)

JDK: Temurin 21.0.12 (`/home/server/.cache/yui/jdk-21-temurin/bin` first on
PATH); Maven via `scripts/phase4/maven-toolchain.sh`.

| Gate | Command | Result |
|---|---|---|
| T055 RED | `npx vitest run src/authorization.test.js` | `Tests 5 failed \| 15 passed` — exactly the new hospital expectations |
| T055 GREEN | same | `Tests 20 passed (20)` |
| T056 RED | `npx vitest run src/features/network/NetworkContextSelector.test.jsx` | import failure: `NetworkContextSelector.jsx` missing |
| T056/T057 GREEN | same | `Tests 14 passed (14)` |
| Backend pins (fresh) | `mvn test -Dtest='MultiBranchOperationsApiTest,NetworkHierarchyApiTest'` | `Tests run: 43 + 9, Failures: 0, Errors: 0`, BUILD SUCCESS |
| Focused auth/security/hierarchy (T061) | `mvn test -Dtest='HierarchicalActingContextApiTest,SecurityAuthorizationTest,MultiBranchOperationsApiTest,NetworkHierarchyApiTest'` | **Tests run: 104, Failures: 0, Errors: 0, Skipped: 0**, BUILD SUCCESS |
| Full backend (T061) | `mvn test` | **Tests run: 291, Failures: 0, Errors: 0, Skipped: 0**, BUILD SUCCESS (278 US1 baseline + 11 + 2; the 2 inherited pins now migrated) |
| Full frontend (T061) | `npm test` | **Test Files 17 passed (17), Tests 284 passed (284)** |
| Typecheck (T061) | `npm run typecheck` (strict, generated client) | clean |
| Production build (T061) | `npm run build` | `✓ built in 3.83s` |
| OpenAPI drift | `bash scripts/phase4/check-openapi-drift.sh` | exit 0 — "no drift — tracked artifacts match the served contract" |
| Whitespace | `git diff --check` | clean |
| Public artifacts | `bash scripts/phase5/check-public-artifacts.sh` | clean, exit 0 |

## 4. Scope, security, and environment audit

- Session-changed paths are exactly the packet allowlist: the two backend
  test files, `frontend/src/auth.js`, `frontend/src/authorization.test.js`,
  `frontend/src/AppShell.jsx`, `frontend/src/AppShell.test.jsx`,
  `frontend/src/DashboardPage.test.jsx`,
  `frontend/src/features/branches/actingContextApi.js` (under the allowed
  `frontend/src/features`), the new
  `frontend/src/features/network/NetworkContextSelector{,.test.jsx}`, plus
  evidence/tasks/reports. The superseded
  `features/branches/BranchSelector.jsx` and its standalone test are
  intentionally preserved untouched (they stay green; removal is an owner
  decision). No generated contract artifact changed in this packet.
- The frozen login parser seam is documented above: sessions stored by
  `parseActingSessionPayload` carry the hospital through the assignment
  views, and the context key resolves it server-issued-field by
  server-issued-field; no invented or client-expanded hospital authority
  exists anywhere.
- Protected env (`/home/server/.config/medicore/runtime.env`,
  `/home/server/medicore-runtime/trial.env`) never read; runs used H2 and
  Testcontainers disposable stores only.
- No dependency install, no commit/push/PR/merge/deploy/release. T062 and
  later remain unchecked and unstarted.
