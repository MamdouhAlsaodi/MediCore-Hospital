# MediCore Phase 4 Security Threat Model

Status: Phase 4 security hardening evidence (plan Task 9, tasks T058–T066).
Boundary: educational, non-clinical system, synthetic data only. This document
makes no production, clinical, compliance, or penetration-testing claims.

## 1. Scope and trust boundaries

The security surface considered here is the deployed review stack:
browser SPA → same-origin nginx `/api` route → Spring Boot API → PostgreSQL.
Authentication, acting assignment, organization/branch scope, lifecycle
transitions, audit context, and data visibility stay server-owned (constitution
principle II). A client-provided identifier never widens authority; every
token carries the server-issued assignment context and the server re-derives
it on every request.

Trust boundaries:

1. Browser → nginx: untrusted network. The SPA is served with a hardened
   header set; the browser never holds anything beyond the session bearer
   token in memory.
2. nginx → API: trusted same-origin proxy hop inside the review stack. The
   API treats every request as unauthenticated until the JWT filter accepts
   it, and keys rate limiting on the direct socket address (the nginx
   service address), never on forwarded headers.
3. API → PostgreSQL: scoped persistence; branch-scoped rows are filtered in
   repositories/services, not in the client.

## 2. Assets

- User credentials (bcrypt-hashed) and the JWT signing secret (runtime
  environment only, never in tracked files or logs).
- Acting-context JWTs (short-lived bearer tokens bound to one assignment).
- Synthetic patient/appointment/admission/invoice/audit records.
- Actuator endpoints (liveness/readiness must stay orchestratable; everything
  else must stay authenticated).

## 3. Threats and mitigations

| # | Threat | Mitigation (phase 4) | Evidence |
|---|--------|----------------------|----------|
| T-1 | Brute-force credential guessing on `POST /api/auth/login` | Bounded expiring login rate limiter keyed by the request's direct socket address: blocks after 5 failures inside a 15-minute window (defaults, profile-independent), generic non-enumerating 429 with `Retry-After`, immediate recovery on success, automatic recovery after the window, strictly bounded store (10,000 tracked addresses; expired purged before least-recently-active eviction) | `LoginRateLimiter`, `AuthController`; `LoginRateLimiterTest`, `LoginRateLimitIntegrationTest` |
| T-2 | Client spoofing `X-Forwarded-For` (or similar) to rotate the rate-limit key | No trusted-proxy configuration exists in this system; only `HttpServletRequest#getRemoteAddr` is ever consulted, and the integration test proves a spoofed forwarded header cannot rotate or bypass a block | `LoginRateLimitIntegrationTest` |
| T-3 | Cross-origin browser abuse (CSRF-style cross-site calls, preflight probing) | Explicit CORS allowlist (`hospital.security.cors.allowed-origins`) that is EMPTY by default in every shipped profile — fail-closed in the production-like postgres/review profile; unknown origins get 403 with no `Access-Control-Allow-*` headers; credentials are never allowed (bearer-token API, no cookies) | `SecurityConfig`; `CorsPolicyTest`; profile defaults in `application.yml`, `application-postgres.yml` |
| T-4 | Content injection / clickjacking / MIME confusion on API responses | Hardened API header set on every response: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, strict `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` (an API serves JSON, never active content) | `SecurityConfig`; `SecurityHeadersTest` |
| T-5 | Same attacks on the SPA shell and static assets | nginx carries the SPA-appropriate set in EVERY response block (server level, hashed-asset location, `/api` location — nginx `add_header` inheritance means a location-level set replaces the server-level one): restrictive self-only CSP allowing exactly what the built app uses, `frame-ancestors 'none'`, `object-src 'none'`, nosniff, DENY, no-referrer; same-origin `/api` routing keeps working | `frontend/nginx.conf`; `scripts/phase4/check-security-headers.sh` (static + live container evidence) |
| T-6 | JWT forgery: expired-but-signed tokens, tampered signatures | Fail closed with the generic 401 on reads and writes; structurally valid control token authenticates, proving the refusal is the expiry, not the mechanism | `HttpSecurityBoundaryMatrixTest` (extends the long-standing `SecurityAuthorizationTest` matrix) |
| T-7 | Privilege escalation via forged role/branch/assignment headers or altered claims | Server-derived authority only; forged `X-Acting-*`/`X-Forwarded-For` headers cannot push a write through and the refused write persists nothing | `SecurityAuthorizationTest`; `HttpSecurityBoundaryMatrixTest` |
| T-8 | Role/branch over-reach by authenticated users | Role-family matrix on every named surface (anonymous refused; permitted roles admitted; denied roles 403; disabled account/assignment and deleted assignment fail closed on every path) | `SecurityAuthorizationTest` (38 tests) |
| T-9 | Information enumeration through login/context errors | One generic 401 body for every credential failure; one generic 429 body for every blocked caller; context refusals share one controlled 403 shape | `AuthController`; `SecurityAuthorizationTest`; `LoginRateLimitIntegrationTest` |
| T-10 | Actuator over-exposure | Anonymous surface is exactly `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`; `/actuator`, `prometheus`, `metrics`, `env`, `beans`, `configprops`, `loggers` stay authenticated-refused | `HttpSecurityBoundaryMatrixTest.actuatorExposureStaysExactlyHealthPlusAuthenticatedPrometheus` |
| T-11 | Security refusals causing side effects (mutation or success audit noise) | Batch proof: anonymous, expired-token, tampered-token, denied-role, and forged-header writes create zero persisted rows and zero audit events; failed and rate-limited logins create zero audit events | `HttpSecurityBoundaryMatrixTest.everyRefusedRequestClassCreatesZeroMutationsAndZeroSuccessAuditEvents`; `LoginRateLimitIntegrationTest.rateLimitRefusalsAndFailuresCreateZeroAuditEvents` |
| T-12 | Observability leakage | Structured logs and metrics reject token/password/body/patient canaries and keep label cardinality bounded (Phase 4 observability tasks T053–T056) | `docs/runbooks/observability.md` |

## 4. Frontend write-caller inventory (T058)

Every browser write path, inventoried before the global CORS/header policy
change. All transports use RELATIVE paths, so every call in every mode is
same-origin: the Vite dev/preview proxy in development and the review nginx
`/api` route in the container stack. No frontend code sets
`credentials: 'include'` — the API is bearer-token authenticated and
cookie-free — so the fail-closed empty CORS allowlist and
`allowCredentials=false` cannot break any demonstrated workflow.

### 4.1 Raw `fetch` paths (not via the shared transport)

| Call site | Method + path | Headers set |
|---|---|---|
| `frontend/src/api.js` `authenticate` | `POST /api/auth/login` | `Content-Type: application/json` only |
| `frontend/src/features/branches/actingContextApi.js` `switchContext` | `POST /api/auth/context` | `Authorization: Bearer`, `Content-Type: application/json` |
| `frontend/src/features/branches/actingContextApi.js` `fetchOrganizationView` | `GET /api/organization` | `Authorization: Bearer` |

### 4.2 Shared transport

`frontend/src/api.js` `apiFetch(path, { method, token, body, onUnauthorized })`
is the single shared transport for every feature module; it sets only
`Authorization: Bearer` and `Content-Type: application/json`.

### 4.3 Write callers (POST/PUT; the frontend has no PATCH or DELETE caller)

| Endpoint | Transport | Module |
|---|---|---|
| `POST /api/auth/login` | raw fetch | `api.js` |
| `POST /api/auth/context` | raw fetch | `actingContextApi.js` |
| `POST /api/patients` | apiFetch | `patientApi.js` |
| `PUT /api/patients/{id}` | apiFetch | `patientApi.js` |
| `POST /api/appointments` | apiFetch | `appointmentApi.js` |
| `POST /api/admissions` | apiFetch | `admissionApi.js` |
| `PUT /api/admissions/{id}/bed` | apiFetch | `admissionApi.js` |
| `PUT /api/admissions/{id}/status` | apiFetch | `admissionApi.js` |
| `POST /api/beds` | apiFetch | `bedApi.js` |
| `PUT /api/beds/{id}/status` | apiFetch | `bedApi.js` |
| `POST /api/invoices` | apiFetch | `invoiceApi.js` |
| `PUT /api/invoices/{id}/status` | apiFetch | `invoiceApi.js` |
| `POST /api/emergency-visits` | apiFetch | `emergencyApi.js` |
| `PUT /api/emergency-visits/{id}/status` | apiFetch | `emergencyApi.js` |

Read-only modules (`dashboardApi.js`, `auditApi.js`, `staffApi.js`,
`availabilityApi.js`) issue GET only. `auth.js` performs no HTTP.

## 5. Security configuration surface

- `hospital.security.login.max-failures` (default 5), `.window` (default
  `PT15M`), `.max-tracked-addresses` (default 10000) — profile-independent
  defaults in `application.yml`.
- `hospital.security.cors.allowed-origins` (env
  `MEDICORE_CORS_ALLOWED_ORIGINS`) — EMPTY by default in both shipped
  profiles; the postgres/review profile is fail-closed unless an operator
  deliberately allows an origin. Allowed methods/headers are explicit
  allowlists (`GET/POST/PUT/DELETE`; `Authorization`, `Content-Type`,
  `X-Correlation-Id`), `Retry-After` is exposed, credentials are never
  allowed.
- nginx: `server_tokens off`; hardened header set repeated in every response
  block (see T-5).

## 6. Limitations and explicit exclusions

Truthful limitations of this phase (no undisclosed gaps):

1. No refresh tokens. The access token is short-lived (60 minutes); expiry
   means an explicit re-login. Refresh-token work is explicitly OUT of scope.
2. No MFA and no SSO/OIDC federation. Username/password with bcrypt only;
   MFA/SSO work is explicitly OUT of scope.
3. No trusted-proxy / `X-Forwarded-For` support. The rate limiter keys on
   the direct socket address. Behind the review nginx this means all browser
   traffic shares the proxy's address as the limiter key — accepted posture
   for the same-origin review stack; any future trusted-proxy design is a
   new, explicit decision with its own tests.
4. The rate limiter is single-node in-memory. It is not a distributed
   rate-limit store; a multi-replica deployment would need a shared store,
   which is out of scope.
5. Focused security tests run on the isolated H2 flow; PostgreSQL-profile
   semantics (migrations, constraints, concurrency) are proven separately by
   the Phase 4 PostgreSQL evidence. No H2 result is presented as PostgreSQL
   proof.
6. No penetration testing, external audit, WAF, or DDoS protection is
   included. The rate limiter bounds credential-guessing on one login
   surface; it is not a general abuse-control claim.
7. The SPA CSP allows `style-src 'unsafe-inline'` because React applies
   CSSOM style attributes at runtime; this is the documented minimum for the
   current build and is revisited only with a nonce/hashed-CSS build change.
8. The browser SPA CSP and the API CSP are deliberately different sets; the
   API set (`default-src 'none'`) reaches browsers through the same-origin
   `/api` proxy and is verified live.

## 7. Verification map

| Gate | Command / source |
|---|---|
| Rate limiter unit + integration matrix | `LoginRateLimiterTest`, `LoginRateLimitIntegrationTest` |
| CORS allowlist / preflight / fail-closed | `CorsPolicyTest` |
| API security headers | `SecurityHeadersTest` |
| Authorization boundary matrix + actuator + zero side effects | `SecurityAuthorizationTest`, `HttpSecurityBoundaryMatrixTest` |
| nginx headers + live same-origin routing | `scripts/phase4/check-security-headers.sh` |
| Public tracked-artifact hygiene | `scripts/phase4/check-public-artifacts.sh` |
