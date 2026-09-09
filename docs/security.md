# Security Notes

MediCore is an **educational, non-clinical training project**. These notes describe the local training build only and make no production-readiness or clinical-safety claim.

- JWT authentication is enabled for API routes other than login and health (`SecurityConfig` permits only `/api/auth/**` and `/actuator/health`; unauthenticated requests receive `401` so clients can distinguish an expired session from a refused role).
- Roles are enforced server-side in `SecurityConfig`: explicit endpoint-family rules and method-level write rules (patient create/update and appointment create are ADMIN/RECEPTIONIST; the staff-directory read additionally allows RECEPTIONIST while staff writes stay ADMIN/HR) are ordered before an ADMIN-only `/api/**` catch-all, so unmatched paths default to deny.
- Passwords are BCrypt-hashed.
- Mutating operations create audit events; failed operations create neither records nor events.
- Secrets exist only in the runtime environment; none are checked in. `HOSPITAL_JWT_SECRET` is required at startup (`application.yml` reads it with no default) and `HOSPITAL_ADMIN_PASSWORD` (at least 12 characters) creates the initial `admin` account exactly once — only when it does not exist yet; an existing account is never modified and no default password exists in code. `.env.example` documents the variable names only.
- Production hardening still requires secret management, refresh-token rotation, MFA/SSO decision, rate limiting, CSRF/CORS review, least-privilege authorization per resource, encryption and data-retention policies.
