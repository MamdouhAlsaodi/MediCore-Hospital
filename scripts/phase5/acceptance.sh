#!/usr/bin/env bash
# Canonical Phase 5 rehearsal (T157-T159). Synthetic portfolio data only.
# Stop at the first non-zero stage. Exit 2 means a missing capability, never PASS.
# No commit, publish, deploy, or durable database is performed by this script.
set -u
set -o pipefail
HERE="$(cd "${BASH_SOURCE[0]%/*}" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SELF="$HERE/acceptance.sh"

blocked() { printf 'phase5-acceptance: BLOCKED %s\n' "$*" >&2; exit 2; }
fail() { printf 'phase5-acceptance: FAIL %s\n' "$*" >&2; exit 1; }
pass() { printf 'phase5-acceptance: PASS %s\n' "$*"; }

# The Java toolchain must exist before any stage begins. Override JAVA_HOME/MVN
# from the caller; do not pin a developer's machine path into public scripts.
java_bin() { if [ -n "${JAVA_HOME:-}" ]; then printf '%s/bin/java' "$JAVA_HOME"; else command -v java; fi; }
check_capability() {
  local tool java_path
  for tool in docker psql pg_dump pg_restore pg_isready node npm git curl; do
    command -v "$tool" >/dev/null 2>&1 || blocked "missing $tool"
  done
  java_path="$(java_bin)" || blocked 'missing JDK 21'
  [ -x "$java_path" ] || blocked 'missing JDK 21'
  "$java_path" -version 2>&1 | grep -q 'version "21\.' || blocked 'Java is not JDK 21'
  # Phase 4's observability probe calls `java` by name, not via JAVA_HOME.
  command -v java >/dev/null 2>&1 || blocked 'JDK 21 bin must be on PATH'
  java -version 2>&1 | grep -q 'version "21\.' || blocked 'PATH java is not JDK 21'
  if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/javac" ] || blocked 'missing JDK 21 javac'
  else
    command -v javac >/dev/null 2>&1 || blocked 'missing JDK 21 javac'
  fi
  if [ -n "${MVN:-}" ]; then
    [ -x "$MVN" ] || blocked 'MVN override is not executable'
  else
    command -v mvn >/dev/null 2>&1 || blocked 'missing Maven (set MVN)'
  fi
  docker info >/dev/null 2>&1 || blocked 'Docker daemon unreachable'
  docker compose version >/dev/null 2>&1 || blocked 'Docker Compose unavailable'
}

# A truly constrained PATH, not a status stub: remove exactly one executable
# at a time and call the same --check-capability preflight. All 4 must exit 2.
negative_capability_proof() {
  local dir tool cmd target rc java_home
  dir="$(mktemp -d "${TMPDIR:-$HOME/.cache}/medicore-phase5-probes.XXXXXX")" || return 2
  for tool in docker psql java node; do
    mkdir -p "$dir/$tool" || { rm -rf "$dir"; return 2; }
    for cmd in docker psql pg_dump pg_restore pg_isready node npm git curl java javac mvn grep; do
      [ "$cmd" = "$tool" ] && continue
      if [ "$cmd" = java ] || [ "$cmd" = javac ]; then
        if [ -n "${JAVA_HOME:-}" ]; then target="$JAVA_HOME/bin/$cmd"; else target="$(command -v "$cmd" || :)"; fi
      elif [ "$cmd" = mvn ] && [ -n "${MVN:-}" ]; then
        target="$MVN"
      else target="$(command -v "$cmd" || :)"; fi
      [ -n "$target" ] && [ -x "$target" ] && ln -s "$target" "$dir/$tool/$cmd"
    done
    java_home="${JAVA_HOME:-}"
    [ "$tool" = java ] && java_home=''
    PATH="$dir/$tool" JAVA_HOME="$java_home" MVN="${MVN:-}" \
      /usr/bin/bash "$SELF" --check-capability >/dev/null 2>&1
    rc=$?
    if [ "$rc" -ne 2 ]; then
      rm -rf "$dir"
      fail "missing-$tool proof expected BLOCKED exit 2, got $rc"
    fi
    pass "missing-$tool exits 2"
  done
  rm -rf "$dir"
}

cleanup_proof() {
  local kind name
  for kind in containers volumes networks; do
    case "$kind" in
      containers) names="$(docker ps -a --format '{{.Names}}')" || return 1 ;;
      volumes) names="$(docker volume ls --format '{{.Name}}')" || return 1 ;;
      networks) names="$(docker network ls --format '{{.Name}}')" || return 1 ;;
    esac
    while IFS= read -r name; do
      case "$name" in
        medicore-review-e2e*|medicore_phase5_*|medicore-phase5*)
          printf 'phase5-acceptance: leftover %s: %s\n' "$kind" "$name" >&2; return 1 ;;
      esac
    done <<< "$names"
  done
}

# Scan the exact changed/untracked candidate set PLUS all Phase 5 specs and
# this gate. Git-ignored runtime artifacts cannot silently enter publication:
# they are not candidates for a normal git add; staged files are in the index.
public_artifact_scan() {
  local paths=() entry
  while IFS= read -r -d '' entry; do paths+=("$entry"); done < <(
    cd "$ROOT" && {
      git diff --name-only -z HEAD --;
      git ls-files --others --exclude-standard -z --;
      git ls-files -z -- specs/005-enterprise-hospital-network-operations;
    } | sort -zu
  )
  paths+=("scripts/phase5/acceptance.sh")
  [ "${#paths[@]}" -gt 1 ] || return 1
  "$HERE/check-public-artifacts.sh" "${paths[@]}"
}

docs_gate() {
  local id n file
  for file in "$ROOT/specs/005-enterprise-hospital-network-operations/traceability.md" \
              "$ROOT/docs/evidence/phase5-verification.md"; do
    [ -f "$file" ] || return 1
    for n in $(seq 1 35); do printf -v id 'FR-%03d' "$n"; grep -q "$id" "$file" || return 1; done
    for n in $(seq 1 14); do printf -v id 'SC-%03d' "$n"; grep -q "$id" "$file" || return 1; done
  done
  for file in docs/architecture/phase5-enterprise-network.md docs/security/threat-model-phase5.md; do
    [ -s "$ROOT/$file" ] || return 1
  done
  git -C "$ROOT" diff --check
}

# One owned clean Compose run proves checkpoints 10-12 together; after it
# exits, assert their distinct output facts instead of falsely relabeling an
# echo/no-op as an independent test. The child script owns and tears down all
# its containers/networks/volumes on BOTH success and failure.
compose_proof() {
  local output="$1" rc=0
  bash "$HERE/container-network-journey.sh" run > "$output" 2>&1 || rc=$?
  # Preserve the real child evidence on failure, but never credentials.
  if [ "$rc" -ne 0 ]; then
    grep -E 'Error:|failed|passed|BLOCKED|REFUSED|Playwright finished|torn down' "$output" >&2 || :
    return "$rc"
  fi
  grep -q 'stack healthy (pg, api, web)' "$output" || return 1
  grep -q '7 passed' "$output" || return 1
  grep -q '3 skipped' "$output" || return 1
  grep -q 'migration idempotency proven:' "$output" || return 1
  grep -q "stack 'medicore-review-e2e' torn down" "$output" || return 1
  grep -q 'Playwright finished with status 0' "$output" || return 1
  return 0
}

run_stage() {
  local name="$1"; shift
  printf 'phase5-acceptance: START %s\n' "$name"
  "$@" || fail "stage $name (see command output)"
  pass "$name"
}

case "${1:-}" in
  --list-stages)
    printf '%s\n' capability-negative-proof public-artifact-scan phase4-baseline \
      empty-migrations phase4-shaped-migration backend-suite postgres-concurrency \
      frontend-tests-build openapi-drift compose-health desktop-mobile-journey \
      restart-recovery audit-log-metrics docs-traceability disposable-cleanup-proof
    exit 0 ;;
  --check-capability) check_capability; pass 'capability preflight'; exit 0 ;;
  --capability-probe) negative_capability_proof; exit 0 ;;
  '') ;;
  *) fail 'usage: acceptance.sh [--list-stages|--check-capability|--capability-probe]' ;;
esac

check_capability
RUN_DIR="$(mktemp -d "${TMPDIR:-$HOME/.cache}/medicore-phase5-acceptance.XXXXXX")" || blocked 'cannot create scratch evidence directory'
trap 'rm -rf "$RUN_DIR"' EXIT
run_stage capability-negative-proof negative_capability_proof
run_stage public-artifact-scan public_artifact_scan
# Phase 4's legacy wrapper invokes Maven with only `test`; inject the
# Testcontainers Docker API floor through Maven's supported MAVEN_ARGS seam.
# This preserves its full baseline gate without modifying the Phase 4 script.
run_stage phase4-baseline env MAVEN_ARGS="-Dapi.version=1.44 ${MAVEN_ARGS:-}" bash "$ROOT/scripts/phase4/acceptance.sh"
run_stage empty-migrations "$ROOT/scripts/phase4/maven-toolchain.sh" -Dapi.version=1.44 -Dtest=Phase5MigrationIntegrationTest test
run_stage phase4-shaped-migration bash "$HERE/migrate-phase4-shaped-postgres.sh"
run_stage backend-suite "$ROOT/scripts/phase4/maven-toolchain.sh" -Dapi.version=1.44 test
run_stage postgres-concurrency bash "$HERE/test-transfer-concurrency.sh"
run_stage frontend-tests-build bash -c 'set -e; cd "$1/frontend"; npm ci --ignore-scripts --no-audit --no-fund; npm test -- --run; npm run typecheck; npm run build' bash "$ROOT"
run_stage openapi-drift bash "$ROOT/scripts/phase4/check-openapi-drift.sh"
printf 'phase5-acceptance: START compose-health + desktop-mobile-journey + restart-recovery\n'
compose_proof "$RUN_DIR/compose.log" || fail 'Compose health/browser/recovery proof'
pass 'compose-health'; pass 'desktop-mobile-journey'; pass 'restart-recovery'
run_stage audit-log-metrics "$ROOT/scripts/phase4/maven-toolchain.sh" -Dapi.version=1.44 '-Dtest=NetworkAuditIsolationApiTest,StructuredLogSanitizationIntegrationTest,MetricsBoundaryIntegrationTest' test
run_stage docs-traceability docs_gate
run_stage disposable-cleanup-proof cleanup_proof
pass 'CANONICAL PHASE 5 ACCEPTANCE: 15 checkpoints passed; synthetic portfolio evidence only'
