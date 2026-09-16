#!/usr/bin/env bash
# Shared Phase 4 Maven toolchain resolution (canonical-acceptance hardening).
#
# One entry point for every Phase 4 script that needs Maven (backend tests,
# Flyway migration wrapper, OpenAPI jar build). Resolution order:
#   1. MVN environment override (an operator-provided Maven entry point);
#   2. `mvn` on PATH (a JDK 21 / Maven 3.9+ host toolchain);
#   3. the built-in containerized Maven — the documented default for hosts
#      without a local JDK 21 toolchain. The whole repository is mounted at
#      /workspace (relative `backend/...` module paths stay inside the
#      mount), the Maven container shares the host network namespace, and
#      TESTCONTAINERS_HOST_OVERRIDE is passed through (default 127.0.0.1)
#      because Testcontainers' inside-container detection otherwise returns
#      the bridge-gateway address, which cannot reach the loopback-only
#      port binding of the restartable PostgreSQL test harness (observed
#      connection-refused failure). The override is overridable and no
#      machine-specific path is hardcoded.
#
# Usage (from anywhere; always targets this repository's backend module):
#   maven-toolchain.sh <maven args...>       e.g. `test`, `-q flyway:migrate -D...`
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

containerized() {
  docker run --rm --network host \
    -e TESTCONTAINERS_HOST_OVERRIDE="${TESTCONTAINERS_HOST_OVERRIDE:-127.0.0.1}" \
    -v "$REPO_ROOT":/workspace \
    -v "${DOCKER_SOCK:-/var/run/docker.sock}":/var/run/docker.sock \
    -v "${MAVEN_CACHE:-$HOME/.m2/repository}":/root/.m2/repository \
    -w /workspace \
    maven:3.9-eclipse-temurin-21 \
    mvn -f backend/pom.xml "$@"
}

if [ -n "${MVN:-}" ]; then
  cd "$REPO_ROOT" && exec "$MVN" -f backend/pom.xml "$@"
elif command -v mvn >/dev/null 2>&1; then
  cd "$REPO_ROOT" && exec mvn -f backend/pom.xml "$@"
elif command -v docker >/dev/null 2>&1; then
  containerized "$@"
else
  echo "maven-toolchain: no usable Maven toolchain (MVN override, mvn on PATH, or docker) found" >&2
  exit 2
fi
