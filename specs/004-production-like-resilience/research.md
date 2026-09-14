# Phase 4 Research and Decisions

## Selected components

- **Schema migration:** Flyway Community integrated through Spring Boot.
- **Database verification:** Testcontainers PostgreSQL for guarded integration/concurrency tests; H2 remains the lightweight developer/unit path.
- **Containers:** multi-stage backend and frontend Dockerfiles plus one root Compose review stack.
- **Observability:** Spring Boot Actuator, Micrometer Prometheus registry, structured JSON logging, bounded HTTP metrics, existing correlation ID.
- **API contracts:** springdoc-openapi generation plus a deterministic OpenAPI artifact and generated TypeScript client committed as derived code.
- **Backup/restore:** PostgreSQL native custom-format backup, `pg_restore --list`, restore into an explicitly disposable database, allowlisted invariant verifier.

## Decisions

1. Production-like does not mean production: no real data, public exposure, SLA, certification, or clinical claim.
2. PostgreSQL schema authority is Flyway; Hibernate only validates.
3. H2 tests remain fast, but PostgreSQL-only semantics require separate real integration gates.
4. Liveness reports process health; readiness includes database/migration readiness.
5. Metrics/logs never use patient, user, branch, assignment, resource, token, or correlation values as unbounded labels.
6. Login rate limiting uses a bounded expiring store and direct socket address unless a trusted-proxy policy is explicitly configured; arbitrary forwarded headers are not authority.
7. Existing access-token architecture is preserved; immediate account/assignment revocation already occurs through per-request reload. Refresh tokens/MFA/SSO are out of scope.
8. Time is stored as unambiguous instants and rendered using the acting branch's IANA zone. Nonexistent DST local times are rejected; ambiguous local times require explicit offset or deterministic documented choice with tests.
9. Department reads become acting-branch scoped. Global MRN/invoice uniqueness remains unchanged by default.
10. Generated contracts are derived artifacts; hand editing is forbidden and a drift check regenerates into a temporary directory.
