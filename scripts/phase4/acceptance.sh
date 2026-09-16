#!/usr/bin/env bash
# Canonical Phase 4 acceptance entry point (T085-T087, plan Task 12).
#
# ONE fail-fast command that composes every required Phase 4 gate in
# dependency order. Every stage executes a REAL repository command as a
# child process; the child's exit status is the stage verdict. There is no
# `|| true`, no test suppression, no echo-only proof, and no H2 substitution
# where PostgreSQL evidence is required. The first failing stage aborts the
# run with exit 1 (fail-fast); the terminal summary is printed only when
# every stage exited 0.
#
# Stage order (plan Task 12 step 1, serial):
#   0. capability-negative-proof  T087: with Docker deliberately invisible
#                                 and with an unreachable daemon, this
#                                 script must exit 2 (BLOCKED), never 0.
#                                 Safe probe: mutates no infrastructure.
#   1. script-self-tests          negative-safety self-tests of the guarded
#                                 Phase 4 helpers (target guard, scanner).
#   2. public-artifact-scan       tracked-file credential/private-data scan.
#   3. backend-suite              full backend `mvn test` (JDK 21): unit +
#                                 real PostgreSQL Testcontainers migration,
#                                 integration, and concurrency gates.
#   4. frontend-tests-build       frontend install + vitest suite + strict
#                                 typecheck + production build.
#   5. openapi-drift              served contract vs tracked OpenAPI YAML +
#                                 generated TypeScript client, byte-exact.
#   6. container-static           Dockerfile/Compose/nginx static guard.
#   7. backup-restore             guarded backup/checksum/restore/invariants
#                                 + negative refusals (real PostgreSQL).
#   8. security-headers           static + live same-origin header matrix.
#   9. observability-live         live liveness/readiness split, DB-loss and
#                                 recovery on the SAME process, log/metrics
#                                 sanitization (real disposable PostgreSQL).
#  10. container-journey          builds both images, boots the disposable
#                                 Compose review stack, proves service/port
#                                 ownership, runs the full Playwright E2E
#                                 suite (desktop+mobile journeys, context
#                                 discrimination, backend restart, PostgreSQL
#                                 loss/recovery, exact non-2xx matrix), and
#                                 tears the stack down on exit.
#  11. docs-traceability          evidence file maps every FR-001..020 and
#                                 SC-001..010; README documents this
#                                 command; tasks T085-T097 exist.
#  12. disposable-cleanup-proof   verifies NO Phase 4 disposable container
#                                 remains on the host after the run.
#
# Cleanup ownership (T086): this script creates exactly ONE thing — its
# mktemp evidence directory — and its EXIT trap removes ONLY that directory.
# Containers, databases, volumes, networks, and ports stay owned by the
# existing Phase 4 child scripts, each of which already traps teardown to
# its own explicitly named disposable resources (medicore_phase4_* databases
# and containers, the medicore-review-e2e compose project). Stage 12 is a
# read-only verification that those traps kept their promises; it never
# touches unrelated host containers or services.
#
# Capability semantics (T087): when a required tool is missing or the
# Docker daemon is unreachable, this script exits 2 with a BLOCKED line —
# a capability gap can never masquerade as PASS.
#
# Toolchain: the backend stage resolves Maven in order — the MVN environment
# override, `mvn` on PATH (JDK 21 / Maven 3.9+), then a built-in containerized
# Maven (see the stage-3 comment). Nothing machine-specific is hardcoded.
#
# Usage:
#   acceptance.sh                  full canonical acceptance (fail-fast)
#   acceptance.sh --list-stages    print the stage list and exit
#   acceptance.sh --check-capability   preflight only: exit 0 capable,
#                                      exit 2 BLOCKED (no other action)
#   acceptance.sh --capability-probe   run ONLY the T087 negative probes
#                                      (safe, no infrastructure mutation);
#                                      exit 0 iff both probes prove BLOCKED
set -u
set -o pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
SELF="$HERE/acceptance.sh"

ok()   { printf 'acceptance: PASS  %s\n' "$*"; }
info() { printf 'acceptance: ----  %s\n' "$*"; }
die()  { printf 'acceptance: FAIL  %s\n' "$*" >&2; exit 1; }
blocked() { printf 'acceptance: BLOCKED  %s\n' "$*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# T086: the ONLY resource this script owns is its evidence directory.
# ---------------------------------------------------------------------------
EVIDENCE_DIR="$(mktemp -d /tmp/medicore_phase4_acceptance.XXXXXX)" || exit 2
cleanup() { rm -rf "$EVIDENCE_DIR"; }
trap cleanup EXIT INT TERM

MVN_CMD="${MVN:-mvn}"
BASH_ABS="$(command -v bash)"

# ---------------------------------------------------------------------------
# Capability preflight (T087). Exits 2 when anything required is missing.
# Read-only: `docker info`/`docker compose version` are daemon pings only.
# ---------------------------------------------------------------------------
check_capability() {
  local cmd
  for cmd in docker psql node npm npx curl git; do
    command -v "$cmd" >/dev/null 2>&1 || blocked "capability: required command not found: $cmd"
  done
  # Backend toolchain: MVN override, host mvn, or the built-in containerized
  # Maven (which requires docker — checked below).
  docker info >/dev/null 2>&1 || blocked "capability: docker daemon unreachable"
  docker compose version >/dev/null 2>&1 || blocked "capability: docker compose not available"
  return 0
}

# ---------------------------------------------------------------------------
# T087: capability-negative proof. Two safe probes:
#   (a) PATH without any required tool  -> preflight must exit 2;
#   (b) a `docker` shim whose daemon ping fails -> preflight must exit 2.
# Neither probe starts containers, touches databases, or writes state; they
# only invoke this script's own read-only preflight under a constrained PATH.
# ---------------------------------------------------------------------------
capability_negative_proof() {
  local probe_path probe_bin rc

  # (a) every required command invisible -> must BLOCKED, never PASS.
  probe_path="$EVIDENCE_DIR/nopath"
  mkdir -p "$probe_path"
  PATH="$probe_path" "$BASH_ABS" "$SELF" --check-capability >/dev/null 2>&1
  rc=$?
  [ "$rc" -ne 0 ] || die "capability-negative (a): preflight returned 0 with no commands on PATH — false PASS possible"
  [ "$rc" -eq 2 ] || die "capability-negative (a): expected exit 2 (BLOCKED), got $rc"
  ok "capability-negative (a): tool-free PATH exits 2 (BLOCKED), not PASS"

  # (b) every required command present but the docker daemon unreachable
  # -> must BLOCKED, never PASS. The shim dir shadows every preflight
  # command; the docker shim's `docker info` ping fails with 127.
  probe_bin="$EVIDENCE_DIR/fakedocker"
  mkdir -p "$probe_bin"
  for shim in docker psql node npm npx curl git mvn; do
    printf '#!/usr/bin/env bash\n[ "$1" = info ] && exit 127\nexit 0\n' > "$probe_bin/$shim"
    chmod +x "$probe_bin/$shim"
  done
  PATH="$probe_bin" "$BASH_ABS" "$SELF" --check-capability >/dev/null 2>&1
  rc=$?
  [ "$rc" -ne 0 ] || die "capability-negative (b): preflight returned 0 with an unreachable docker daemon — false PASS possible"
  [ "$rc" -eq 2 ] || die "capability-negative (b): expected exit 2 (BLOCKED), got $rc"
  ok "capability-negative (b): unreachable daemon exits 2 (BLOCKED), not PASS"
}

# ---------------------------------------------------------------------------
# Stage runner: executes a real child command, streams its output, and
# propagates its exit status. No suppression anywhere.
# ---------------------------------------------------------------------------
STAGE_START=0
run_stage() {
  local name="$1"; shift
  info "stage: $name — start"
  STAGE_START=$(date +%s)
  if ! "$@"; then
    printf 'acceptance: FAIL  stage %s exited non-zero after %ss — fail-fast abort\n' \
      "$name" "$(( $(date +%s) - STAGE_START ))" >&2
    exit 1
  fi
  ok "stage $name ($(( $(date +%s) - STAGE_START ))s)"
}

docs_traceability_gate() {
  local id
  local evidence="$REPO_ROOT/docs/evidence/phase4-verification.md"
  [ -f "$evidence" ] || { echo "FAIL: missing $evidence" >&2; return 1; }
  for id in $(seq 1 20); do
    printf -v id 'FR-%03d' "$id"
    grep -q -- "$id" "$evidence" || { echo "FAIL: $evidence has no $id evidence entry" >&2; return 1; }
  done
  for id in $(seq 1 10); do
    printf -v id 'SC-%03d' "$id"
    grep -q -- "$id" "$evidence" || { echo "FAIL: $evidence has no $id evidence entry" >&2; return 1; }
  done
  grep -q 'scripts/phase4/acceptance.sh' "$REPO_ROOT/README.md" \
    || { echo "FAIL: README.md does not document the canonical acceptance command" >&2; return 1; }
  grep -q 'Phase 4' "$REPO_ROOT/docs/traceability.md" \
    || { echo "FAIL: docs/traceability.md has no Phase 4 section" >&2; return 1; }
  for t in $(seq 85 97); do
    printf -v tid 'T%03d ' "$t"
    grep -q "$tid" "$REPO_ROOT/specs/004-production-like-resilience/tasks.md" \
      || { echo "FAIL: tasks.md has no T0$t entry" >&2; return 1; }
  done
  # Tracked tree must stay whitespace-clean and free of conflict markers.
  git -C "$REPO_ROOT" diff --check || return 1
  ! grep -rn '^<<<<<<< \|^>>>>>>> ' "$REPO_ROOT/docs" "$REPO_ROOT/README.md" "$REPO_ROOT/scripts/phase4" "$REPO_ROOT/e2e" 2>/dev/null \
    || { echo "FAIL: unresolved conflict marker found" >&2; return 1; }
  return 0
}

disposable_cleanup_proof() {
  # Read-only: every Phase 4 disposable container must be gone. All Phase 4
  # disposable containers are named with the medicore_phase4_ / medicore-phase4
  # prefixes or belong to the named e2e compose project. Unrelated host
  # containers are intentionally invisible to this check.
  local leftovers
  leftovers="$(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E '^(medicore_phase4_|medicore-phase4|medicore-review-e2e)' || true)"
  if [ -n "$leftovers" ]; then
    echo "FAIL: Phase 4 disposable containers still present after run:" >&2
    echo "$leftovers" >&2
    return 1
  fi
  # The named e2e compose volume must be gone as well (down -v removes it).
  if docker volume ls --format '{{.Name}}' 2>/dev/null | grep -q '^medicore-review_pgdata$'; then
    echo "FAIL: e2e compose volume medicore-review_pgdata still present" >&2
    return 1
  fi
  return 0
}

case "${1:-}" in
  --list-stages)
    cat <<'EOF'
capability-negative-proof
script-self-tests
public-artifact-scan
backend-suite
frontend-tests-build
openapi-drift
container-static
backup-restore
security-headers
observability-live
container-journey
docs-traceability
disposable-cleanup-proof
EOF
    exit 0 ;;
  --check-capability)
    check_capability
    info "capability check: all required tools present, docker daemon reachable"
    exit 0 ;;
  --capability-probe)
    capability_negative_proof
    info "capability-negative proof: BLOCKED semantics proven (exit 2, never PASS)"
    exit 0 ;;
esac

RUN_START=$(date +%s)
info "canonical Phase 4 acceptance — repository: $REPO_ROOT"
info "HEAD: $(git -C "$REPO_ROOT" rev-parse HEAD)"

# NOTE: the tree intentionally carries uncommitted T085-T097 changes at
# acceptance time (committing is outside this packet). The reviewed state is
# identified by the HEAD recorded above plus the changed-path inventory in
# docs/evidence/phase4-verification.md.

check_capability
ok "capability preflight"

# Stage 0 (T087) — prove a capability gap can never yield PASS.
run_stage "capability-negative-proof" capability_negative_proof

# Stage 1 — negative-safety self-tests of the guarded helpers.
run_stage "script-self-tests" bash -c '
  set -e
  "$1/scripts/phase4/test-postgres-target-guard.sh" >/dev/null
  echo "  target-guard self-test: ok"
  "$1/scripts/phase4/test-check-public-artifacts.sh" >/dev/null
  echo "  public-artifact-scanner self-test: ok"
' bash "$REPO_ROOT"

# Stage 2 — public tracked-artifact safety scan.
run_stage "public-artifact-scan" "$HERE/check-public-artifacts.sh" "$REPO_ROOT"

# Stage 3 — full backend suite (unit + real PostgreSQL Testcontainers gates),
# through the shared Maven toolchain resolution (scripts/phase4/maven-toolchain.sh:
# MVN override -> host mvn -> built-in containerized Maven). The helper mounts
# the WHOLE repository (OpenApiContractTest reads the tracked ../api/openapi
# artifact relative to the module, which must stay inside the mount) and
# passes TESTCONTAINERS_HOST_OVERRIDE through (default 127.0.0.1): the
# containerized Maven shares the host network namespace while the restartable
# PostgreSQL harness binds its port to host loopback ONLY — Testcontainers'
# inside-container detection would otherwise return the bridge-gateway
# address, which cannot reach a loopback binding (observed: connection
# refused, full-suite failure). The override is overridable and no
# machine-specific path is hardcoded.
run_stage "backend-suite" "$HERE/maven-toolchain.sh" test

# Stage 4 — frontend install, unit suite, strict typecheck, production build.
run_stage "frontend-tests-build" bash -c '
  set -e
  cd "$1/frontend"
  npm install --no-audit --no-fund >/dev/null
  echo "  npm install: ok"
  npm test
  npm run typecheck
  npm run build
' bash "$REPO_ROOT"

# Stage 5 — served-contract vs tracked OpenAPI + generated client.
run_stage "openapi-drift" "$HERE/check-openapi-drift.sh"

# Stage 6 — static container/Compose/nginx guard (fails closed pre-build).
run_stage "container-static" "$HERE/check-container-static.sh"

# Stage 7 — guarded backup/checksum/restore/invariants + refusals.
run_stage "backup-restore" "$HERE/test-backup-restore.sh"

# Stage 8 — security-header matrix (static + live same-origin probes).
run_stage "security-headers" "$HERE/check-security-headers.sh"

# Stage 9 — live liveness/readiness split, DB loss/recovery, sanitization.
run_stage "observability-live" "$HERE/observability-live-rehearsal.sh"

# Stage 10 — images, Compose health, ownership proof, full Playwright E2E
# (desktop+mobile journeys, restart, PostgreSQL loss/recovery), teardown.
run_stage "container-journey" "$HERE/container-e2e-stack.sh" run

# Stage 11 — documentation and traceability evidence gate.
run_stage "docs-traceability" docs_traceability_gate

# Stage 12 — prove every Phase 4 disposable resource was really removed.
run_stage "disposable-cleanup-proof" disposable_cleanup_proof

printf 'acceptance: ===========================================================================\n'
printf 'acceptance: CANONICAL PHASE 4 ACCEPTANCE: PASS — all 13 stages exited 0 in %ss\n' \
  "$(( $(date +%s) - RUN_START ))"
printf 'acceptance: HEAD %s — backend/frontend/containers/backup/security/e2e/docs all green\n' \
  "$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
exit 0
