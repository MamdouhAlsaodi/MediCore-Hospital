#!/usr/bin/env bash
# Shared library for Phase 4 OpenAPI generation and drift checking
# (plan Task 10, T069/T074; FR-016).
#
# Isolation contract for every boot performed through this library:
#   - the backend runs in a THROWAWAY Docker container (eclipse-temurin:21-jre,
#     already required by the backend image) that dies with the script;
#   - the datasource is a process-local in-memory H2 database that exists only
#     inside that container (generation proves nothing about persistence, so no
#     PostgreSQL state is created and no disposable volume is needed);
#   - every credential is generated in-process from the OS CSPRNG
#     (/dev/urandom), passed only to the container environment, NEVER printed,
#     logged, or persisted, and never inherited from any runtime environment;
#   - explicit command-line overrides pin the datasource, Flyway (off), demo
#     seeding (off), review accounts (off), and loopback binding, so no
#     protected runtime value can influence the boot;
#   - the server binds 127.0.0.1 only (--network host, loopback address).
#
# Sourcing scripts must set -euo pipefail themselves and provide docker(1).
# No value printed by this library may ever contain a credential or token.
set -euo pipefail

OPENAPI_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OPENAPI_JAR="$OPENAPI_REPO_ROOT/backend/target/medicore-hospital-0.29.0.jar"

# ---------------------------------------------------------------------------
# Random process-local credential (OS CSPRNG). Printed on stdout ONLY to the
# capturing caller inside the same script process; never logged by callers.
# ---------------------------------------------------------------------------
openapi_random_secret() {
    local bytes="${1:-48}"
    head -c "$bytes" /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'
}

# ---------------------------------------------------------------------------
# Rebuild the backend jar through the project's containerized Maven runner if
# the tracked main sources or the pom are newer than the jar (or it is absent).
# ---------------------------------------------------------------------------
openapi_ensure_jar() {
    if [ -f "$OPENAPI_JAR" ]; then
        local stale
        stale="$(find "$OPENAPI_REPO_ROOT/backend/src/main" "$OPENAPI_REPO_ROOT/backend/pom.xml" \
            -newer "$OPENAPI_JAR" -print -quit 2>/dev/null || true)"
        if [ -z "$stale" ]; then
            echo "lib-openapi: backend jar is current: $OPENAPI_JAR" >&2
            return 0
        fi
        echo "lib-openapi: backend sources changed since last build; rebuilding" >&2
    else
        echo "lib-openapi: backend jar missing; building" >&2
    fi
    (cd "$OPENAPI_REPO_ROOT" && "$OPENAPI_REPO_ROOT/scripts/phase4/maven-toolchain.sh" -q -DskipTests package)
}

# ---------------------------------------------------------------------------
# Pick a loopback port whose connect() fails right now (best effort; the boot
# itself is retried with a fresh port on failure).
# ---------------------------------------------------------------------------
openapi_free_port() {
    local port
    while :; do
        port=$((5503 + RANDOM % 4000))
        if ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
            echo "$port"
            return 0
        fi
    done
}

# ---------------------------------------------------------------------------
# Wait until readiness reports healthy (deps prove the login surface works).
# Args: PORT [ATTEMPTS]. Polls anonymously; prints nothing secret.
# ---------------------------------------------------------------------------
openapi_wait_readiness() {
    local port="$1" attempts="${2:-60}" i code
    for i in $(seq 1 "$attempts"); do
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/actuator/health/readiness" 2>/dev/null || true)"
        [ "$code" = "200" ] && return 0
        sleep 2
    done
    return 1
}

# ---------------------------------------------------------------------------
# One full boot attempt: start, wait, login. On failure the container is
# removed and the function returns non-zero so the caller can retry with a
# fresh name/port. Prints the access token on stdout ONLY on success.
# Args: NAME PORT
# ---------------------------------------------------------------------------
openapi_boot() {
    local name="$1" port="$2" jwt_secret admin_password token_body
    jwt_secret="$(openapi_random_secret 48)"
    admin_password="$(openapi_random_secret 24)"
    docker run -d --name "$name" --network host \
        -e HOSPITAL_JWT_SECRET="$jwt_secret" \
        -e HOSPITAL_ADMIN_PASSWORD="$admin_password" \
        -v "$OPENAPI_JAR":/app.jar:ro \
        eclipse-temurin:21-jre \
        java -jar /app.jar \
        --server.address=127.0.0.1 \
        --server.port="$port" \
        "--spring.datasource.url=jdbc:h2:mem:openapi-gen-${RANDOM}-${RANDOM};MODE=PostgreSQL;DB_CLOSE_DELAY=-1" \
        --spring.jpa.hibernate.ddl-auto=create-drop \
        --spring.flyway.enabled=false \
        --medicore.demo.seed=true \
        --medicore.review-accounts.enabled=false \
        >/dev/null || return 1
    if ! openapi_wait_readiness "$port"; then
        docker rm -f "$name" >/dev/null 2>&1 || true
        return 1
    fi
    token_body="$(curl -s --max-time 20 -X POST -H 'Content-Type: application/json' \
        -d "{\"username\":\"admin\",\"password\":\"$admin_password\"}" \
        "http://127.0.0.1:$port/api/auth/login" || true)"
    if ! printf '%s' "$token_body" | node -e "
let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{
  try { const t=JSON.parse(d).accessToken; if (typeof t==='string'&&t.length>0) { process.stdout.write(t); process.exit(0);} } catch {}
  process.exit(1);
});" ; then
        docker rm -f "$name" >/dev/null 2>&1 || true
        return 1
    fi
}

# ---------------------------------------------------------------------------
# Remove the disposable container unconditionally (safe in traps).
# ---------------------------------------------------------------------------
openapi_stop_backend() {
    docker rm -f "$1" >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------
# Fetch both document flavours with a bearer token. Args: PORT TOKEN TMPDIR
# Writes $TMPDIR/api-docs.yaml and $TMPDIR/api-docs.json. Never prints the
# token; curl errors fail the script (set -euo pipefail).
# ---------------------------------------------------------------------------
openapi_fetch_docs() {
    local port="$1" token="$2" tmp="$3"
    curl -fsS --max-time 60 -H "Authorization: Bearer $token" \
        "http://127.0.0.1:$port/v3/api-docs.yaml" -o "$tmp/api-docs.yaml"
    curl -fsS --max-time 60 -H "Authorization: Bearer $token" \
        "http://127.0.0.1:$port/v3/api-docs" -o "$tmp/api-docs.json"
}
