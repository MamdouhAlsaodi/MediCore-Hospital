#!/usr/bin/env bash
# Guarded native backup of a disposable synthetic PostgreSQL review
# database (plan Task 7; FR-006). PostgreSQL custom-format archive plus a
# SHA-256 checksum, restrictive permissions, and zero credential echo.
# The disposable-target guard runs FIRST and any refusal aborts before any
# connection or mutation. The archive is validated with `pg_restore --list`
# before success is reported.
#
# Usage:
#   backup-postgres.sh <host> <port> <database> <user> <output-base>
# Environment:
#   PGPASSWORD   password for <user> (required; never printed)
# Output:
#   <output-base>.dump       custom-format archive (mode 600)
#   <output-base>.dump.sha256  checksum sidecar (mode 600)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/postgres-target-guard.sh"

if [ $# -ne 5 ]; then
  echo "usage: $0 <host> <port> <database> <user> <output-base>" >&2
  exit 2
fi
HOST="$1"; PORT="$2"; DB="$3"; USER_="$4"; OUT="$5"

if [ -z "${PGPASSWORD:-}" ]; then
  echo "backup-postgres: PGPASSWORD must be set in the environment (never a default)" >&2
  exit 2
fi
command -v pg_dump >/dev/null 2>&1 || { echo "pg_dump not found" >&2; exit 2; }
command -v pg_restore >/dev/null 2>&1 || { echo "pg_restore not found" >&2; exit 2; }

# Fail-closed authority: refuse an unsafe target before touching anything.
"$GUARD" check "$HOST" "$DB" || exit 1

export PGPASSWORD
START="$(date +%s)"
if ! pg_dump -Fc -h "$HOST" -p "$PORT" -U "$USER_" -d "$DB" -f "$OUT.dump"; then
  echo "backup-postgres: pg_dump failed; refusing to emit a partial archive" >&2
  rm -f "$OUT.dump"
  exit 1
fi
chmod 600 "$OUT.dump"

# Archive integrity probe before declaring success.
if ! pg_restore --list "$OUT.dump" >/dev/null 2>&1; then
  echo "backup-postgres: archive failed pg_restore --list validation" >&2
  rm -f "$OUT.dump"
  exit 1
fi

sha256sum "$OUT.dump" | awk '{print $1 "  " "'"$OUT"'.dump"}' > "$OUT.dump.sha256"
chmod 600 "$OUT.dump.sha256"
END="$(date +%s)"
echo "backup-postgres: PASS archive=$OUT.dump sha256=$(cut -d' ' -f1 "$OUT.dump.sha256") duration_seconds=$((END-START)) (local training measurement, not an SLA/RPO/RTO)"
