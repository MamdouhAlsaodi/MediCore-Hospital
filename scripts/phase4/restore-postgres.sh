#!/usr/bin/env bash
# Guarded restore of a Phase 4 backup into an EXPLICITLY DISPOSABLE, FRESH
# PostgreSQL database (plan Task 7; FR-006). The restore refuses, before
# any mutation: unsafe host/name targets, source==target, a missing or
# corrupt archive, and an existing target database (no overwrite). The
# archive is validated with `pg_restore --list` before the target is
# created. Credentials come from the environment and are never echoed.
#
# Usage:
#   restore-postgres.sh <host> <port> <archive.dump> <target-db> <user>
# Environment:
#   PGPASSWORD   password for <user> (required; never printed)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/postgres-target-guard.sh"

if [ $# -ne 5 ]; then
  echo "usage: $0 <host> <port> <archive.dump> <target-db> <user>" >&2
  exit 2
fi
HOST="$1"; PORT="$2"; ARCHIVE="$3"; TARGET_DB="$4"; USER_="$5"

if [ -z "${PGPASSWORD:-}" ]; then
  echo "restore-postgres: PGPASSWORD must be set in the environment (never a default)" >&2
  exit 2
fi
command -v psql >/dev/null 2>&1 || { echo "psql not found" >&2; exit 2; }
command -v pg_restore >/dev/null 2>&1 || { echo "pg_restore not found" >&2; exit 2; }

[ -f "$ARCHIVE" ] || { echo "restore-postgres: REFUSED: archive not found: $ARCHIVE" >&2; exit 1; }

# Fail-closed authority: disposable target, never the source, before any
# statement can be sent. The guard refuses any non-disposable target name
# and any unsafe host; equality with the source is refused explicitly.
"$GUARD" check "$HOST" "$TARGET_DB" || exit 1
if [ -n "${RESTORE_SOURCE_DB:-}" ]; then
  "$GUARD" check "$HOST" "$RESTORE_SOURCE_DB" >/dev/null || exit 1
  if [ "$RESTORE_SOURCE_DB" = "$TARGET_DB" ]; then
    echo "restore-postgres: REFUSED: target equals source database: $TARGET_DB" >&2
    exit 1
  fi
fi

export PGPASSWORD

# Archive integrity BEFORE any target exists.
if ! pg_restore --list "$ARCHIVE" >/dev/null 2>&1; then
  echo "restore-postgres: REFUSED: archive failed pg_restore --list validation (corrupt or foreign)" >&2
  exit 1
fi

# Fresh-target rule: an existing database is never overwritten.
if psql -h "$HOST" -p "$PORT" -U "$USER_" -d postgres -Atc \
     "select 1 from pg_database where datname = '$TARGET_DB'" | grep -q 1; then
  echo "restore-postgres: REFUSED: target database already exists (no overwrite): $TARGET_DB" >&2
  exit 1
fi

echo "restore-postgres: creating fresh disposable target $HOST/$TARGET_DB"
if ! psql -h "$HOST" -p "$PORT" -U "$USER_" -d postgres -v ON_ERROR_STOP=1 \
     -c "create database \"$TARGET_DB\"" >/dev/null; then
  echo "restore-postgres: target creation failed" >&2
  exit 1
fi

if ! pg_restore -h "$HOST" -p "$PORT" -U "$USER_" -d "$TARGET_DB" --no-owner --exit-on-error "$ARCHIVE"; then
  echo "restore-postgres: pg_restore failed into $TARGET_DB" >&2
  exit 1
fi
echo "restore-postgres: PASS restored archive into disposable target $HOST/$TARGET_DB"
