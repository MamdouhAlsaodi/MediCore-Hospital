# Security Notes

- JWT authentication is enabled for API routes other than login and health.
- Roles are represented in the account model and are ready for method-level restrictions.
- Passwords are BCrypt-hashed.
- Mutating operations create audit events.
- The checked-in JWT secret and development admin are local-development defaults only.
- Production hardening still requires secret management, refresh-token rotation, MFA/SSO decision, rate limiting, CSRF/CORS review, least-privilege authorization per resource, encryption and data-retention policies.
