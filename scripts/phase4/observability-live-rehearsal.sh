#!/usr/bin/env bash
# Phase 4 observability live rehearsal (plan Task 8, T056).
#
# Drives the REAL shipped backend jar against a REAL disposable PostgreSQL
# container and verifies, on live processes:
#   1. liveness stays healthy while readiness fails during database loss;
#   2. readiness recovers on the SAME application process after the database
#      container is started again (explicit loopback port binding, because a
#      dynamic host mapping does not survive a container restart);
#   3. captured console logs and a scraped Prometheus output never contain
#      sensitive canaries submitted through the live HTTP surface;
#   4. every captured log line is one well-formed JSON object and every
#      scraped uri label stays inside the bounded template vocabulary;
#   5. client-side request metrics are never exported (export boundary).
#
# Everything here is process-owned and disposable: the container is named
# with the disposable prefix, its credentials come from the OS CSPRNG and
# are never printed, and the teardown trap removes the container, the
# application process, and all temporary files. No runtime .env, credential
# store, or non-disposable data is touched.
#
# Usage: observability-live-rehearsal.sh
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
BACKEND="$REPO_ROOT/backend"
JAR="$(ls "$BACKEND"/target/medicore-hospital-*.jar 2>/dev/null | head -1)"

fail() { echo "FAIL: $*" >&2; exit 1; }

# --- Preflight ------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "docker not available"
command -v psql >/dev/null 2>&1 || fail "psql not available"
command -v curl >/dev/null 2>&1 || fail "curl not available"
command -v python3 >/dev/null 2>&1 || fail "python3 not available"
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\)\..*/\1/p')"
[ "$JAVA_MAJOR" = "21" ] || fail "java on PATH must be major version 21 (found: '$(java -version 2>&1 | head -1)'); put a JDK 21 first on PATH"
[ -n "$JAR" ] || fail "backend jar missing; build it first (mvn package)"
docker info >/dev/null 2>&1 || fail "docker daemon unreachable"

WORK="$(mktemp -d /tmp/medicore_phase4_obs.XXXXXX)" || fail "mktemp failed"
APP_LOG="$WORK/app-console.json"
SCRAPE="$WORK/prometheus.txt"
CONTAINER="medicore_phase4_observability_pg"
APP_PID=""

cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null
  docker rm -f "$CONTAINER" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

# --- Disposable PostgreSQL on a static loopback binding -------------------
PG_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '\n')"
PG_DB="medicore_phase4_observability"
PG_PORT="$(python3 - <<'EOF'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
EOF
)"
[ -n "$PG_PORT" ] || fail "could not find a free loopback port"

docker rm -f "$CONTAINER" >/dev/null 2>&1
docker run -d --name "$CONTAINER" -p "127.0.0.1:${PG_PORT}:5432" \
  -e "POSTGRES_PASSWORD=${PG_PASSWORD}" -e "POSTGRES_DB=${PG_DB}" \
  postgres:16-alpine >/dev/null 2>&1 || fail "could not start disposable postgres"

for _ in $(seq 1 30); do
  if PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p "$PG_PORT" -U postgres -d "$PG_DB" \
       -v ON_ERROR_STOP=1 -c "select 1" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
PGPASSWORD="$PG_PASSWORD" psql -h 127.0.0.1 -p "$PG_PORT" -U postgres -d "$PG_DB" \
  -v ON_ERROR_STOP=1 -c "select 1" >/dev/null 2>&1 || fail "disposable postgres never became reachable"

# --- Shipped migrations through the guarded wrapper -----------------------
export PGPASSWORD="$PG_PASSWORD"
# The wrapper resolves its own Maven toolchain (MVN override -> mvn ->
# containerized Maven); fail closed here only when none can exist.
if [ -z "${MVN:-}" ] && ! command -v mvn >/dev/null 2>&1; then
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
    || fail "no Maven toolchain (MVN/mvn/docker) for the migration wrapper"
fi
"$HERE/migrate-disposable-postgres.sh" 127.0.0.1 "$PG_PORT" "$PG_DB" postgres >/dev/null 2>&1 \
  || fail "guarded migration of the disposable target failed"

# --- Launch the real application (postgres review profile) ----------------
JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
ADMIN_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '\n')"
PORT="$(python3 - <<'EOF'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
EOF
)"

DB_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/${PG_DB}" \
DB_USER="postgres" DB_PASSWORD="$PG_PASSWORD" \
HOSPITAL_JWT_SECRET="$JWT_SECRET" HOSPITAL_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
nohup java -jar "$JAR" --server.port="$PORT" --spring.profiles.active=postgres \
  --medicore.demo.seed=true \
  > "$APP_LOG" 2>&1 &
APP_PID=$!

probe() { curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}$1"; }

READY=""
for _ in $(seq 1 60); do
  if [ "$(probe /actuator/health/readiness)" = "200" ]; then READY="yes"; break; fi
  sleep 2
done
[ -n "$READY" ] || { tail -c 400 "$APP_LOG" >&2; fail "application never became ready"; }
echo "ok: application ready on 127.0.0.1:${PORT} (disposable postgres on 127.0.0.1:${PG_PORT})"

# --- Canaries through the live HTTP surface -------------------------------
CANARY_TOKEN="CANARY-BEARER-TOKEN-$(head -c 6 /dev/urandom | base64 | tr -d '\n=+/')"
CANARY_PASSWORD="CANARY-LOGIN-PASSWORD-$(head -c 6 /dev/urandom | base64 | tr -d '\n=+/')"
CANARY_PATH="CANARY-PATH-ID-$(head -c 6 /dev/urandom | base64 | tr -d '\n=+/')"
TOKEN=""
for _ in 1 2 3; do
  RESP="$(curl -s -X POST "http://127.0.0.1:${PORT}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"${ADMIN_PASSWORD}\"}")"
  TOKEN="$(printf '%s' "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
  [ -n "$TOKEN" ] && break
  sleep 2
done
[ -n "$TOKEN" ] || fail "admin review login failed against the live stack"

curl -s -o /dev/null "http://127.0.0.1:${PORT}/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"${CANARY_PASSWORD}\"}"
curl -s -o /dev/null "http://127.0.0.1:${PORT}/api/patients" -H "Authorization: Bearer ${CANARY_TOKEN}"
curl -s -o /dev/null "http://127.0.0.1:${PORT}/api/patients/${CANARY_PATH}" -H "Authorization: Bearer ${TOKEN}"
echo "ok: sensitive-value canaries submitted to the live boundary"

# --- Live database loss ----------------------------------------------------
docker stop -t 2 "$CONTAINER" >/dev/null 2>&1 || fail "docker stop failed"
LOST=""
for _ in $(seq 1 120); do
  L="$(probe /actuator/health/liveness)"
  R="$(probe /actuator/health/readiness)"
  if [ "$L" != "200" ]; then fail "liveness became unhealthy during database loss (status ${L})"; fi
  if [ "$R" != "200" ]; then LOST="yes"; break; fi
  sleep 1
done
[ -n "$LOST" ] || fail "readiness never failed while the database was down"
echo "ok: during database loss liveness stayed 200 and readiness turned non-200"

# --- Live recovery on the same process -------------------------------------
docker start "$CONTAINER" >/dev/null 2>&1 || fail "docker start failed"
RECOVERED=""
for _ in $(seq 1 120); do
  if [ "$(probe /actuator/health/readiness)" = "200" ]; then RECOVERED="yes"; break; fi
  sleep 1
done
[ -n "$RECOVERED" ] || fail "readiness never recovered after the database returned"
[ "$(probe /actuator/health/liveness)" = "200" ] || fail "liveness was not healthy after recovery"
echo "ok: readiness recovered to 200 on the same process after the database returned"

# --- Captured telemetry leakage checks --------------------------------------
curl -s -o "$SCRAPE" "http://127.0.0.1:${PORT}/actuator/prometheus" \
  -H "Authorization: Bearer ${TOKEN}" || fail "prometheus scrape failed"
[ -s "$SCRAPE" ] || fail "prometheus scrape body was empty"

python3 - "$APP_LOG" "$SCRAPE" "$CANARY_TOKEN" "$CANARY_PASSWORD" "$CANARY_PATH" "$TOKEN" "$ADMIN_PASSWORD" <<'EOF'
import json, re, sys

log_path, scrape_path, canary_token, canary_password, canary_path, token, admin_password = sys.argv[1:8]
lines = [l for l in open(log_path, encoding="utf-8", errors="replace").read().splitlines() if l.strip()]
if not lines:
    sys.exit("FAIL: application console log is empty")
for i, line in enumerate(lines):
    try:
        node = json.loads(line)
    except Exception as e:
        sys.exit(f"FAIL: console line {i} is not one JSON object: {e}; offending line starts with: {line[:120]!r}")
    if not isinstance(node, dict) or "message" not in node or "level" not in node:
        sys.exit(f"FAIL: console line {i} lacks the bounded base fields")
print("ok: every captured console line is one well-formed JSON object with the base fields")

banned = [canary_token, canary_password, canary_path, token, admin_password]
scrape = open(scrape_path, encoding="utf-8").read()
for b in banned:
    if b in scrape:
        sys.exit("FAIL: a sensitive canary leaked into the metrics output")
    if any(b in l for l in lines):
        sys.exit("FAIL: a sensitive canary leaked into the console log")
print("ok: no canary (bearer token, login password, path id, issued token, admin password) appears in logs or metrics")

if "http_server_requests_seconds" not in scrape:
    sys.exit("FAIL: the bounded server request metric is missing from the scrape")
if "http_client_requests" in scrape:
    sys.exit("FAIL: client-side request metrics were exported")
print("ok: server request metric present; client request metrics never exported")

allowed = {"UNKNOWN", "root", "/api/auth/login", "/api/patients", "/api/patients/{id}",
           "/api/appointments", "/api/admissions", "/api/emergency", "/api/invoices",
           "/api/branches", "/api/departments", "/actuator/prometheus", "/actuator/health",
           "/actuator/health/readiness", "/actuator/health/liveness", "/actuator/health/**", "/error"}
uri_labels = set(re.findall(r'uri="([^"]*)"', scrape))
unexpected = {u for u in uri_labels if u not in allowed}
if unexpected:
    sys.exit(f"FAIL: route labels outside the bounded template set: {sorted(unexpected)}")
for u in uri_labels:
    if "CANARY" in u:
        sys.exit("FAIL: a canary path reached a uri label")
print(f"ok: all {len(uri_labels)} scraped uri label values are bounded templates")
EOF
[ "$?" -eq 0 ] || fail "telemetry checks failed (see FAIL line above)"
echo "PASS: observability live rehearsal (loss, recovery, sanitization, bounded metrics)"
