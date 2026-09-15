# Observability Runbook (Phase 4, training/review)

Scope: the local review stack only. MediCore is an educational, non-clinical
system running synthetic data; nothing here is a production, clinical, or
compliance statement, and no SLA/RPO/RTO or production threshold is claimed
or implied.

## Surfaces

| Surface | Endpoint | Exposure |
|---|---|---|
| Liveness | `GET /actuator/health/liveness` | anonymous, process-only (`livenessState`) |
| Readiness | `GET /actuator/health/readiness` | anonymous, process + database (`readinessState,db`) |
| Prometheus | `GET /actuator/prometheus` | authenticated only (anonymous requests get 401) |
| Structured console log | process stdout | single output, one JSON object per line |

Every other actuator endpoint is unexposed (`management.endpoints.web.exposure.include: health,prometheus`).

## Live verification (all commands below were executed; results recorded)

### 1. Liveness/readiness under real database loss and recovery

Automated (Testcontainers, `docker stop`/`docker start` of a disposable
PostgreSQL container bound to an explicit loopback port):

```
mvn test -Dtest='LivenessReadinessDbLossIntegrationTest'
-> Tests run: 1, Failures: 0, Errors: 0
```

Observed contract on the live process (verified twice: Testcontainers run and
the shell rehearsal below): with the database stopped, liveness stays `200`
while readiness turns non-`200`; after the container starts again, readiness
returns to `200` on the same application process. The container must use a
STATIC loopback port binding — a dynamically mapped host port does not
survive a container restart on this daemon (probe-verified), which would make
recovery impossible for a process whose JDBC URL is fixed at startup.

### 2. Shell rehearsal: loss, recovery, and telemetry sanitization

```
# requires: JDK 21 first on PATH, backend jar built (mvn package), docker, psql
./scripts/phase4/observability-live-rehearsal.sh
```

Fresh run output (loopback ports are ephemeral per run):

```
ok: application ready on 127.0.0.1:58077 (disposable postgres on 127.0.0.1:33329)
ok: sensitive-value canaries submitted to the live boundary
ok: during database loss liveness stayed 200 and readiness turned non-200
ok: readiness recovered to 200 on the same process after the database returned
ok: every captured console line is one well-formed JSON object with the base fields
ok: no canary (bearer token, login password, path id, issued token, admin password) appears in logs or metrics
ok: server request metric present; client request metrics never exported
ok: all 5 scraped uri label values are bounded templates
PASS: observability live rehearsal (loss, recovery, sanitization, bounded metrics)
```

The rehearsal creates one disposable `medicore_phase4_*` container with
CSPRNG credentials, migrates it through the guarded wrapper
(`migrate-disposable-postgres.sh`), and removes everything on exit (trap).

### 3. Probing the review stack manually

```
curl -s http://127.0.0.1:<backend-port>/actuator/health/liveness     # 200, process-only
curl -s http://127.0.0.1:<backend-port>/actuator/health/readiness    # 200 with db up; non-200 while db is down
curl -s http://127.0.0.1:<backend-port>/actuator/prometheus          # 401 anonymous
curl -s http://127.0.0.1:<backend-port>/actuator/prometheus \
     -H "Authorization: Bearer <token>"                              # 200, exposition text
```

### 4. Structured log shape

Each console line is exactly one JSON object rendered by the production
encoder (`StructuredJsonEncoder`), carrying the bounded fields:

```
{"timestamp":"<ISO-8601 instant>","level":"<LEVEL>","logger":"<name>",
 "thread":"<name>","message":"<text>","correlationId":"<bounded id>",
 "http_method":"<method>","http_route":"<route template>",
 "http_status":"<status>","duration_bucket":"<fixed bucket>"}
```

- `correlationId` is the SAME single correlation authority set by
  `CorrelationIdFilter` (mirrored onto the MDC; echoed in the
  `X-Correlation-Id` response header). No second authority exists.
- `http_route` is the matched route TEMPLATE (e.g. `/api/patients/{id}`) —
  never a raw path-variable value. Requests that match no handler are
  recorded as `unmatched` (log surface) / `UNKNOWN` (metrics surface).
- `duration_bucket` is one of five fixed buckets
  (`under_100ms`, `100_499ms`, `500_999ms`, `1_4s`, `5s_or_more`) — never a
  raw duration value.
- Actuator requests are not observed by the request-observation filter.
- The startup banner is disabled (`spring.main.banner-mode: off`) so the
  console stream stays purely structured (verified live: every captured
  line parsed as one JSON object).

### 5. Metrics boundary

- `http.server.requests` is the only HTTP request metric. Its label keys are
  the framework's fixed low-cardinality set (`error`, `exception`, `method`,
  `outcome`, `status`, `uri`); `uri` values are templates or the fixed
  `UNKNOWN`/`root` placeholders.
- Client-side request metrics (`http.client.requests`) are denied at the
  registry by `MetricsBoundaryConfig` and never exported — verified live:
  no `http_client_requests` sample appears in a scrape.
- Sensitive-value canaries (bearer token, login password, path-variable id,
  the issued access token, the admin password) submitted to the live surface
  were verified absent from BOTH the captured logs and the scraped output.

## Known boundaries (honest limitations)

- Migration state gates STARTUP itself (Flyway runs before the application
  context exists to answer probes); readiness therefore proves process +
  database state, and Boot 3.5 ships no Flyway health contributor.
- Readiness consultation of the database is bounded by the 2s pool
  connection-timeout on the PostgreSQL profile so probes stay responsive
  during loss; this is a review-stack measurement, not an availability
  promise.
- Telemetry covers process health, HTTP requests, and JVM/system gauges
  shipped by the framework. No business metrics are exported yet.
