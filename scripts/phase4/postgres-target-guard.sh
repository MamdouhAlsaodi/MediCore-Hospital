#!/usr/bin/env bash
# Disposable PostgreSQL target guard (plan Tasks 3/7; constitution IV).
#
# The single fail-closed authority for every Phase 4 migration, backup, and
# restore command that may touch a database. A target is acceptable ONLY
# when BOTH hold:
#   1. the host is loopback (localhost, 127.0.0.1, ::1) or the documented
#      Compose service name "postgres"; and
#   2. the database name matches the explicit disposable prefix
#      "medicore_phase4_" plus a non-empty lowercase/digit/underscore body.
# Anything else — including empty arguments, shell metacharacters, real
# sounding names, and private/external hosts — is refused with exit 1
# before any mutation can be attempted.
#
# Usage:
#   postgres-target-guard.sh check     <host> <database>
#   postgres-target-guard.sh check-pair <source-host> <source-db> <target-host> <target-db>
# check-pair additionally refuses target == source (same host AND name).
set -u

DISPOSABLE_PREFIX='medicore_phase4_'
DISPOSABLE_NAME_RE="^${DISPOSABLE_PREFIX}[a-z0-9_]+$"

fail() { echo "postgres-target-guard: REFUSED: $*" >&2; exit 1; }

check_host() {
  case "$1" in
    localhost|127.0.0.1|::1|postgres) return 0 ;;
    *) fail "host '$1' is not loopback or the documented Compose service" ;;
  esac
}

check_name() {
  local name="$1"
  [ -n "$name" ] || fail "database name is empty"
  if ! printf '%s' "$name" | grep -qE "$DISPOSABLE_NAME_RE"; then
    fail "database name '$name' does not match the disposable pattern ${DISPOSABLE_NAME_RE}"
  fi
}

MODE="${1:-}"
case "$MODE" in
  check)
    [ $# -eq 3 ] || fail "usage: $0 check <host> <database>"
    check_host "$2"
    check_name "$3"
    echo "postgres-target-guard: accepted disposable target $2/$3"
    ;;
  check-pair)
    [ $# -eq 5 ] || fail "usage: $0 check-pair <source-host> <source-db> <target-host> <target-db>"
    check_host "$2"
    check_name "$3"
    check_host "$4"
    check_name "$5"
    if [ "$2" = "$4" ] && [ "$3" = "$5" ]; then
      fail "source and target are the same database: $2/$3"
    fi
    echo "postgres-target-guard: accepted disposable pair $2/$3 -> $4/$5"
    ;;
  *)
    fail "usage: $0 check <host> <database> | check-pair <src-host> <src-db> <tgt-host> <tgt-db>"
    ;;
esac
exit 0
