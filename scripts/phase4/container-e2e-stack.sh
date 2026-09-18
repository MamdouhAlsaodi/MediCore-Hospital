#!/usr/bin/env bash
# Phase 4 container E2E stack orchestrator (T077/T078) and guarded container
# control plane (T082/T083).
#
# Ownership model:
#   - `run` generates every credential with the OS CSPRNG (/dev/urandom) into
#     THIS process's environment only (T077) — values are exported to the
#     Compose/Playwright children, never written to a file, never echoed.
#   - `run` starts the disposable Compose review stack on free loopback ports,
#     PROVES service/port ownership (compose attributes every publisher to
#     this run's project; every health endpoint answers) BEFORE any browser
#     executes (T078), then tears the stack down on exit (trap), always.
#   - Control actions (restart-backend, stop-postgres, start-postgres, down,
#     assert-no-pending-migrations, capture) are the ONLY sanctioned way for
#     the E2E suite to touch containers. Every destructive action re-proves
#     ownership: the compose project name must match the disposable pattern
#     AND the target container must carry this run's compose-project label.
#     Anything else is refused before any command touches Docker (fail
#     closed, mirroring postgres-target-guard.sh).
#   - On start failure the run retries EXACTLY once with a fresh project name
#     and fresh ports; two failures abort without leaving containers behind.
set -u
set -o pipefail

REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
COMPOSE_FILE="$REPO_DIR/compose.yaml"
MIGRATION_DIR="$REPO_DIR/backend/src/main/resources/db/migration"

fail() { echo "container-e2e-stack: REFUSED: $*" >&2; exit 1; }
info() { echo "container-e2e-stack: $*"; }

PROJECT="${E2E_STACK_PROJECT:-}"

require_project() {
    [ -n "$PROJECT" ] || fail "E2E_STACK_PROJECT is not set; no stack is owned by this process"
    printf '%s' "$PROJECT" | grep -qE '^medicore-review-e2e[a-z0-9-]*$' \
        || fail "project name '$PROJECT' is not a disposable container-e2e project"
}

require_owned_container() { # $1=service
    require_project
    local cid label
    # -a: a STOPPED container (postgres during the loss rehearsal) is still
    # owned by this project and must be found and startable.
    cid="$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps -aq "$1" 2>/dev/null || true)"
    [ -n "$cid" ] || fail "no container for service '$1' in project '$PROJECT'"
    label="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid" 2>/dev/null || true)"
    [ "$label" = "$PROJECT" ] \
        || fail "container for '$1' does not carry this run's project label; refusing to act"
    printf '%s' "$cid"
}

# --- OS CSPRNG secret generation (never echoed, never persisted) ------------
gen_hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

port_free() {
    local p="$1"
    # Exact parse of the Local Address:Port column; ends with :<port>.
    if ss -ltnH 2>/dev/null | awk '{print $4}' | grep -qE ":${p}$"; then return 1; fi
    return 0
}

pick_port() { # $1=preferred $2..=excluded
    local p="$1" tried=0
    while [ "$tried" -lt 40 ]; do
        local clash=0
        for excluded in "${@:2}"; do [ "$p" = "$excluded" ] && clash=1; done
        if [ "$clash" -eq 0 ] && port_free "$p"; then printf '%s' "$p"; return 0; fi
        p=$((p + 1)); tried=$((tried + 1))
    done
    fail "no free port found starting at $1"
}

export_or_fail() { # $1=NAME $2=value
    [ -n "$2" ] || fail "refusing to export empty $1"
    export "$1=$2"
}

wait_healthy() {
    local deadline=$(( $(date +%s) + ${1:-420} ))
    while :; do
        if "$REPO_DIR/scripts/phase4/wait-for-review-stack.sh" 5 >/dev/null 2>&1; then
            return 0
        fi
        [ "$(date +%s)" -lt "$deadline" ] || { echo "FAIL: stack never became healthy" >&2; return 1; }
        sleep 3
    done
}

prove_ownership() {
    info "service/port ownership proof for project '$PROJECT':"
    local publishers
    publishers="$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps --format json)"
    local service port cid
    for spec in "postgres $REVIEW_PG_HOST_PORT" "backend $REVIEW_API_HOST_PORT" "frontend $REVIEW_WEB_HOST_PORT"; do
        service="${spec%% *}"
        port="${spec##* }"
        # Compose must attribute the exact loopback publisher to this project...
        printf '%s' "$publishers" | grep -Eq "\"PublishedPort\":$port(,|})" || return 1
        # ...the port must be listening on the host (a free port means nothing owns it)...
        if port_free "$port"; then return 1; fi
        # ...and exactly this run's project-owned container must serve it.
        cid="$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps -q "$service")"
        docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid" | grep -q "^$PROJECT$" || return 1
        info "  $service -> 127.0.0.1:$port (container ${cid:0:12}, project-owned)"
    done
    "$REPO_DIR/scripts/phase4/wait-for-review-stack.sh" 60 || return 1
}

action="${1:-}"
case "$action" in

    run)
        # Full orchestrator: CSPRNG env -> clean stack -> ownership proof ->
        # Playwright -> guarded teardown (always).
        cd "$REPO_DIR" || fail "cannot cd to repository root"
        command -v docker >/dev/null || fail "docker is required"
        docker compose version >/dev/null 2>&1 || fail "docker compose is required"
        [ -f "$COMPOSE_FILE" ] || fail "compose.yaml not found"

        # Deterministic root Playwright install (E2E-HARNESS-004): the root
        # manifests are the only source of truth. The pinned runner is
        # installed from the root lockfile BEFORE any credential or container
        # exists, and the test CLI below is invoked strictly from that
        # repository-local install — npx must never fetch a transient runner
        # again (a transient runner cannot resolve root @playwright/test).
        command -v npm >/dev/null || fail "npm is required"
        [ -f "$REPO_DIR/package.json" ] || fail "root package.json not found"
        [ -f "$REPO_DIR/package-lock.json" ] || fail "root package-lock.json not found"
        info "installing pinned root dependencies from package-lock.json (lifecycle scripts, audit, fund disabled)"
        npm ci --ignore-scripts --no-audit --no-fund \
            || fail "deterministic root npm ci failed; refusing to start the e2e stack"

        expected_migrations="$(ls "$MIGRATION_DIR"/V*.sql | wc -l)"
        [ "$expected_migrations" -gt 0 ] || fail "no migration files found"

        attempt_project="medicore-review-e2e"
        # Even the first attempt scans for genuinely free loopback ports:
        # the host may run unrelated services on the preferred defaults.
        attempt_pg="$(pick_port 55432)"
        attempt_api="$(pick_port 5501 "$attempt_pg")"
        attempt_web="$(pick_port 5502 "$attempt_pg" "$attempt_api")"
        overall=1
        for attempt in 1 2; do
            PROJECT="$attempt_project"
            REVIEW_PG_HOST_PORT="$attempt_pg" REVIEW_API_HOST_PORT="$attempt_api" REVIEW_WEB_HOST_PORT="$attempt_web"
            info "attempt $attempt: project '$PROJECT' ports pg=$REVIEW_PG_HOST_PORT api=$REVIEW_API_HOST_PORT web=$REVIEW_WEB_HOST_PORT"

            export_or_fail E2E_STACK_PROJECT "$PROJECT"
            export_or_fail REVIEW_DB_NAME "medicore_phase4_e2e"
            export_or_fail REVIEW_DB_USER "medicore_e2e"
            export_or_fail REVIEW_DB_PASSWORD "$(gen_hex 16)"
            export_or_fail HOSPITAL_JWT_SECRET "$(gen_hex 32)"
            export_or_fail HOSPITAL_ADMIN_PASSWORD "$(gen_hex 16)"
            export_or_fail HOSPITAL_REVIEW_DOCTOR_PASSWORD "$(gen_hex 8)"
            export_or_fail HOSPITAL_REVIEW_NURSE_PASSWORD "$(gen_hex 8)"
            export_or_fail REVIEW_PG_HOST_PORT "$REVIEW_PG_HOST_PORT"
            export_or_fail REVIEW_API_HOST_PORT "$REVIEW_API_HOST_PORT"
            export_or_fail REVIEW_WEB_HOST_PORT "$REVIEW_WEB_HOST_PORT"
            export_or_fail REVIEW_WEB_ORIGIN "http://127.0.0.1:$REVIEW_WEB_HOST_PORT"
            export_or_fail REVIEW_API_ORIGIN "http://127.0.0.1:$REVIEW_API_HOST_PORT"
            export_or_fail E2E_EXPECTED_MIGRATIONS "$expected_migrations"
            export_or_fail E2E_OTHER_BRANCH_LABEL "Demo North Branch"
            export_or_fail E2E_STACK_SCRIPT "$REPO_DIR/scripts/phase4/container-e2e-stack.sh"
            export_or_fail E2E_RUN_TAG "$(date +%m%d%H%M%S)$$_$attempt"

            teardown() { "$SELF" down; }
            trap 'teardown' EXIT

            if docker compose -f "$COMPOSE_FILE" -p "$PROJECT" up -d --build --wait \
                && wait_healthy 420 \
                && prove_ownership; then
                info "stack proven; starting Playwright container E2E suite"
                # Repository-local pinned runner only: --no makes npm exec
                # fail instead of downloading a transient package. Forwarded
                # test args are passed through exactly.
                npm exec --no -- playwright test "${@:2}"
                overall=$?
                info "Playwright finished with status $overall"
            else
                info "attempt $attempt failed to bring up a healthy stack"
                overall=1
            fi

            "$SELF" down || overall=1
            trap - EXIT
            [ "$overall" -eq 0 ] && break

            if [ "$attempt" -eq 1 ]; then
                info "retrying once with a fresh project name and fresh ports"
                attempt_project="medicore-review-e2e-r2"
                attempt_pg=$((attempt_pg + 7)); attempt_api=$((attempt_api + 7)); attempt_web=$((attempt_web + 7))
                # re-pick truly free ports for the retry
                attempt_pg="$(pick_port "$attempt_pg")"
                attempt_api="$(pick_port "$attempt_api" "$attempt_pg")"
                attempt_web="$(pick_port "$attempt_web" "$attempt_pg" "$attempt_api")"
            fi
        done
        exit "$overall"
        ;;

    restart-backend|stop-postgres|start-postgres)
        service="backend"
        [ "$action" = "restart-backend" ] || service="postgres"
        cid="$(require_owned_container "$service")"
        case "$action" in
            restart-backend)
                info "restarting owned backend container ${cid:0:12}"
                docker restart "$cid" >/dev/null
                ;;
            stop-postgres)
                info "stopping owned postgres container ${cid:0:12}"
                docker stop "$cid" >/dev/null
                ;;
            start-postgres)
                info "starting owned postgres container ${cid:0:12}"
                docker start "$cid" >/dev/null
                ;;
        esac
        info "$action: done"
        ;;

    down)
        require_project
        info "tearing down project '$PROJECT' (containers, network, volume)"
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" down -v --remove-orphans \
            || fail "compose down failed for '$PROJECT'"
        for port in "$REVIEW_PG_HOST_PORT" "$REVIEW_API_HOST_PORT" "$REVIEW_WEB_HOST_PORT"; do
            [ -n "${port:-}" ] || continue
            if ! port_free "$port"; then
                sleep 2
                port_free "$port" || fail "port $port still listening after teardown"
            fi
        done
        info "down: stack removed, loopback ports released"
        ;;

    assert-no-pending-migrations)
        require_project
        cid="$(require_owned_container postgres)"
        [ -n "${REVIEW_DB_USER:-}" ] && [ -n "${REVIEW_DB_NAME:-}" ] \
            || fail "REVIEW_DB_USER/REVIEW_DB_NAME are not set"
        expected="${E2E_EXPECTED_MIGRATIONS:-$(ls "$MIGRATION_DIR"/V*.sql | wc -l)}"
        history_db="$(docker exec "$cid" psql -U "$REVIEW_DB_USER" -d "$REVIEW_DB_NAME" -tAc \
            "select count(*) from flyway_schema_history")" || fail "cannot read flyway_schema_history"
        failed_db="$(docker exec "$cid" psql -U "$REVIEW_DB_USER" -d "$REVIEW_DB_NAME" -tAc \
            "select count(*) from flyway_schema_history where success is not true")" \
            || fail "cannot read flyway_schema_history success flags"
        [ "$history_db" = "$expected" ] || fail "applied migrations ($history_db) != shipped migration files ($expected): pending migrations exist"
        [ "$failed_db" = "0" ] || fail "$failed_db failed migration rows present"
        info "assert-no-pending-migrations: applied=$history_db failed=0 shipped=$expected (zero pending)"
        ;;

    capture) # capture backend-container-id
        what="${2:-}"
        [ "$what" = "backend-container-id" ] || fail "unknown capture target '$what'"
        require_owned_container backend
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps -q backend
        ;;

    check)
        # Read-only ownership proof for the currently exported environment.
        prove_ownership
        ;;

    *)
        fail "usage: $0 run [playwright args...] | restart-backend | stop-postgres | start-postgres | down | assert-no-pending-migrations | capture backend-container-id | check"
        ;;
esac
