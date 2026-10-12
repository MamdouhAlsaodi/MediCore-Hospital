#!/usr/bin/env bash
# Phase 5 container journey orchestrator (T151–T154): the clean disposable
# stack, the desktop/mobile browser journey with exact expected non-2xx
# counts, the migration-idempotency + durable-state restart proof, and the
# truthful liveness/readiness PostgreSQL split — always torn down.
#
# Ownership model (inherited from scripts/phase4/container-e2e-stack.sh):
#   - Every credential is generated with the OS CSPRNG into THIS process's
#     environment only — exported to the Compose/Playwright children, never
#     written to a file, never echoed.
#   - The stack starts on free loopback ports; service/port ownership is
#     PROVEN (compose attributes every publisher to this run's project;
#     every health endpoint answers) before any browser executes.
#   - Control actions (restart-backend, stop-postgres, start-postgres, down,
#     assert-no-pending-migrations, capture) are the only sanctioned way for
#     the E2E suite to touch containers, and every destructive action
#     re-proves the compose-project ownership before acting (fail closed).
#   - The trap tears the stack down on every exit path; no media artifact is
#     kept (Playwright trace/screenshot/video are off in the config).
set -u
set -o pipefail

REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
COMPOSE_FILE="$REPO_DIR/compose.yaml"
MIGRATION_DIR="$REPO_DIR/backend/src/main/resources/db/migration"

fail() { echo "container-network-journey: REFUSED: $*" >&2; exit 1; }
info() { echo "container-network-journey: $*"; }

PROJECT="${E2E_STACK_PROJECT:-}"

require_project() {
    [ -n "$PROJECT" ] || fail "E2E_STACK_PROJECT is not set; no stack is owned by this process"
    printf '%s' "$PROJECT" | grep -qE '^medicore-review-e2e[a-z0-9-]*$' \
        || fail "project name '$PROJECT' is not a disposable container-journey project"
}

require_owned_container() { # $1=service
    require_project
    local cid label
    cid="$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps -aq "$1" 2>/dev/null || true)"
    [ -n "$cid" ] || fail "no container for service '$1' in project '$PROJECT'"
    label="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid" 2>/dev/null || true)"
    [ "$label" = "$PROJECT" ] \
        || fail "container for '$1' does not carry this run's project label; refusing to act"
    printf '%s' "$cid"
}

gen_hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

port_free() {
    local p="$1"
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

wait_healthy() { # $1=timeout-seconds
    local deadline=$(( $(date +%s) + "$1" ))
    local pg api web
    pg="127.0.0.1:${REVIEW_PG_HOST_PORT}"
    api="http://127.0.0.1:${REVIEW_API_HOST_PORT}"
    web="http://127.0.0.1:${REVIEW_WEB_HOST_PORT}"
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
                pg_isready -U "$REVIEW_DB_USER" -d "$REVIEW_DB_NAME" >/dev/null 2>&1 \
           && [ "$(curl -s -o /dev/null -w '%{http_code}' "$api/actuator/health" 2>/dev/null)" = "200" ] \
           && [ "$(curl -s -o /dev/null -w '%{http_code}' "$web/" 2>/dev/null)" = "200" ]; then
            info "stack healthy (pg, api, web)"
            return 0
        fi
        sleep 3
    done
    return 1
}

prove_ownership() {
    # Every published port on this project must be attributed by compose to
    # THIS run's project, and every container must carry the project label.
    local publishers publishers_of_project
    publishers="$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps --format json 2>/dev/null | grep -c "\"Project\":[[:space:]]*\"$PROJECT\"" || true)"
    [ "$publishers" -ge 3 ] || { info "compose did not attribute 3 services to $PROJECT"; return 1; }
    for service in postgres backend frontend; do
        local cid label
        cid="$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" ps -aq "$service" 2>/dev/null || true)"
        [ -n "$cid" ] || return 1
        label="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid" 2>/dev/null || true)"
        [ "$label" = "$PROJECT" ] || { info "$service lacks the project label"; return 1; }
    done
    return 0
}

action="${1:-}"
case "$action" in
    run)
        cd "$REPO_DIR" || fail "cannot cd to repository root"
        command -v docker >/dev/null || fail "docker is required"
        docker compose version >/dev/null 2>&1 || fail "docker compose is required"
        [ -f "$COMPOSE_FILE" ] || fail "compose.yaml not found"
        command -v npm >/dev/null || fail "npm is required"
        [ -f "$REPO_DIR/package.json" ] || fail "root package.json not found"
        [ -f "$REPO_DIR/package-lock.json" ] || fail "root package-lock.json not found"
        info "installing pinned root dependencies from package-lock.json (lifecycle scripts, audit, fund disabled)"
        npm ci --ignore-scripts --no-audit --no-fund \
            || fail "deterministic root npm ci failed; refusing to start the journey stack"

        expected_migrations="$(ls "$MIGRATION_DIR"/V*.sql | wc -l)"
        [ "$expected_migrations" -gt 0 ] || fail "no migration files found"

        attempt_project="medicore-review-e2e"
        attempt_pg="$(pick_port 55432)"
        attempt_api="$(pick_port 5501 "$attempt_pg")"
        attempt_web="$(pick_port 5502 "$attempt_pg" "$attempt_api")"
        overall=1
        for attempt in 1 2; do
            PROJECT="$attempt_project"
            REVIEW_PG_HOST_PORT="$attempt_pg" REVIEW_API_HOST_PORT="$attempt_api" REVIEW_WEB_HOST_PORT="$attempt_web"
            info "attempt $attempt: project '$PROJECT' ports pg=$REVIEW_PG_HOST_PORT api=$REVIEW_API_HOST_PORT web=$REVIEW_WEB_HOST_PORT"

            export_or_fail E2E_STACK_PROJECT "$PROJECT"
            export_or_fail REVIEW_DB_NAME "medicore_phase5_journey"
            export_or_fail REVIEW_DB_USER "medicore_journey"
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
            export_or_fail E2E_STACK_SCRIPT "$SELF"
            export_or_fail E2E_RUN_TAG "$(date +%m%d%H%M%S)$$_$attempt"

            teardown() { "$SELF" down; }
            trap 'teardown' EXIT

            if docker compose -f "$COMPOSE_FILE" -p "$PROJECT" up -d --build --wait \
                && wait_healthy 420 \
                && prove_ownership; then
                info "stack proven; starting the Phase 5 journey Playwright suite"
                # Phase 5 specs only: the journey, the context discrimination,
                # and the transfer conflict rehearsal, in that order.
                npm exec --no -- playwright test \
                    e2e/phase5-network-journey.spec.js \
                    e2e/phase5-context-discrimination.spec.js \
                    e2e/phase5-transfer-concurrency.spec.js \
                    e2e/phase5-journey-resilience.spec.js \
                    "${@:2}"
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

    assert-no-pending-migrations)
        require_project
        expected="${E2E_EXPECTED_MIGRATIONS:-$(ls "$MIGRATION_DIR"/V*.sql | wc -l)}"
        applied="$(docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T postgres \
            psql -U "$REVIEW_DB_USER" -d "$REVIEW_DB_NAME" -t -A -c \
            "select count(*) from flyway_schema_history where success = true" 2>/dev/null || echo QUERY_FAILED)"
        [ "$applied" = "$expected" ] \
            || fail "migration proof failed: applied=$applied expected=$expected (pending or failed migration)"
        info "migration idempotency proven: $applied applied migrations, zero pending"
        ;;

    capture) # capture <what>: one owned fact for exact assertions
        require_project
        case "${2:-}" in
            backend-container-id)
                require_owned_container backend
                ;;
            *) fail "unknown capture target '${2:-}'" ;;
        esac
        ;;

    down)
        require_project
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 \
            || fail "teardown failed for project '$PROJECT'"
        info "stack '$PROJECT' torn down (containers, network, volumes)"
        ;;

    *)
        fail "usage: $0 {run [playwright args...] | restart-backend | stop-postgres | start-postgres | assert-no-pending-migrations | capture <what> | down}"
        ;;
esac
