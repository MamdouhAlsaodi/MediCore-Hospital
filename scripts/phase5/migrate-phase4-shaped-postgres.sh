#!/usr/bin/env bash
# T155: guarded Phase 4-shaped PostgreSQL migration rehearsal. The Java test
# owns Testcontainers PostgreSQL and its synthetic databases; this script
# never invokes Compose, connects to a caller-selected database, or persists
# a volume. Testcontainers/Ryuk removes its process-owned container at exit.
set -u
set -o pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
BACKEND_TEST="com.mamtrex.hospital.infrastructure.Phase5MigrationIntegrationTest"
MVN_BIN="${MVN:-mvn}"

fail() { printf 'migrate-phase4-shaped-postgres: FAIL: %s\n' "$*" >&2; exit 1; }
blocked() { printf 'migrate-phase4-shaped-postgres: BLOCKED: %s\n' "$*" >&2; exit 2; }

check_local_docker() {
  command -v docker >/dev/null 2>&1 || blocked "docker is required for Testcontainers"
  docker info >/dev/null 2>&1 || blocked "Docker daemon is unavailable"
  local endpoint
  endpoint="$(docker context inspect "$(docker context show)" --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" \
    || blocked "cannot establish the active Docker endpoint"
  case "$endpoint" in
    unix:///*) ;;
    *) blocked "active Docker endpoint is not a local Unix socket; refusing remote infrastructure" ;;
  esac
  if [ -n "${DOCKER_HOST:-}" ]; then
    case "$DOCKER_HOST" in
      unix:///*) ;;
      *) blocked "DOCKER_HOST is not a local Unix socket; refusing remote infrastructure" ;;
    esac
  fi
}

check_toolchain() {
  [ -x "${JAVA_HOME:-}/bin/java" ] || blocked "JAVA_HOME must point to an executable JDK (21 expected)"
  command -v "$MVN_BIN" >/dev/null 2>&1 || [ -x "$MVN_BIN" ] \
    || blocked "Maven is unavailable (set MVN or add mvn to PATH)"
  [ -f "$REPO_ROOT/backend/pom.xml" ] || fail "backend/pom.xml not found"
  [ -f "$REPO_ROOT/backend/src/test/java/com/mamtrex/hospital/infrastructure/Phase5MigrationIntegrationTest.java" ] \
    || fail "Phase5MigrationIntegrationTest is missing"
  local version
  version="$("$MVN_BIN" -version 2>&1)" || blocked "Maven could not start"
  printf '%s\n' "$version" | grep -Eq 'Java version: 21([.]|$)' \
    || blocked "Maven must run on JDK 21"
}

case "${1:-run}" in
  --check-capability)
    check_local_docker
    check_toolchain
    printf 'migrate-phase4-shaped-postgres: PASS: local Docker and JDK 21/Maven available; no resources started\n'
    exit 0
    ;;
  run)
    [ "$#" -le 1 ] || { printf 'usage: %s [run|--check-capability]\n' "$0" >&2; exit 2; }
    check_local_docker
    check_toolchain
    cd "$REPO_ROOT" || fail "cannot enter repository"
    printf 'migrate-phase4-shaped-postgres: running synthetic Phase 4-shaped V5 backfill/refusal tests on disposable Testcontainers PostgreSQL\n'
    exec "$MVN_BIN" -f backend/pom.xml \
      -Dapi.version=1.44 \
      -Dtest='Phase5MigrationIntegrationTest#v5BackfillsPhase4ShapedDataDeterministically+v5RefusesMalformedHierarchyFixtures+v5RefusesDuplicateLogicalAssignments' \
      test
    ;;
  *) printf 'usage: %s [run|--check-capability]\n' "$0" >&2; exit 2 ;;
esac
