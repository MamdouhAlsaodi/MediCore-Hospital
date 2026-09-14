#!/usr/bin/env bash
# RED acceptance test for scripts/phase4/postgres-target-guard.sh (plan
# Tasks 3/7): the guard must fail closed on unsafe hosts, non-disposable
# database names, missing arguments, and source/target equality, and must
# accept only the documented disposable targets. Exits 0 only when every
# assertion holds.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/postgres-target-guard.sh"
fail() { echo "FAIL: $1" >&2; exit 1; }
pass() { echo "ok: $1"; }

[ -x "$GUARD" ] || fail "postgres-target-guard.sh missing or not executable"

expect_reject() {
  local why="$1"; shift
  if "$GUARD" "$@" >/dev/null 2>&1; then
    fail "guard accepted an unsafe target: $why"
  fi
  pass "guard rejects $why"
}

expect_accept() {
  local why="$1"; shift
  if ! "$GUARD" "$@" >/dev/null 2>&1; then
    fail "guard refused a safe disposable target: $why"
  fi
  pass "guard accepts $why"
}

# --- single-target mode: host + database -----------------------------------
expect_reject "missing arguments" check
expect_reject "empty database name" check 127.0.0.1 ""
expect_reject "external host" check db.example.com medicore_phase4_test
PRIVATE_REMOTE_HOST="$(printf '%s' '10.' '0.' '0.' '5')"
expect_reject "private remote host" check "$PRIVATE_REMOTE_HOST" medicore_phase4_test
expect_reject "live-sounding database name" check 127.0.0.1 medicore
expect_reject "default postgres name" check localhost postgres
expect_reject "wrong prefix" check localhost medicore_review_test
expect_reject "prefix-only name" check localhost medicore_phase4_
expect_reject "host-injection attempt" check "127.0.0.1; rm -rf /" medicore_phase4_test
expect_accept "loopback ipv4 disposable target" check 127.0.0.1 medicore_phase4_test_demo
expect_accept "localhost disposable target" check localhost medicore_phase4_test_demo
expect_accept "compose service disposable target" check postgres medicore_phase4_test_demo

# --- pair mode: source + target (restore path) ------------------------------
expect_reject "pair: target equals source" check-pair 127.0.0.1 medicore_phase4_src 127.0.0.1 medicore_phase4_src
expect_reject "pair: unsafe source host" check-pair db.example.com medicore_phase4_src 127.0.0.1 medicore_phase4_dst
expect_reject "pair: unsafe target name" check-pair 127.0.0.1 medicore_phase4_src localhost medicore
expect_reject "pair: unsafe source name" check-pair 127.0.0.1 medicore 127.0.0.1 medicore_phase4_dst
expect_accept "pair: two distinct disposable targets" check-pair 127.0.0.1 medicore_phase4_src localhost medicore_phase4_dst

echo "PASS: postgres-target-guard RED acceptance"
