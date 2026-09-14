#!/usr/bin/env bash
# Guarded Flyway migration of a disposable PostgreSQL database (plan Task 3).
#
# Applies the shipped, versioned migrations (backend/src/main/resources/db/
# migration) to ONE explicitly disposable target. The disposable-target
# guard runs FIRST and any refusal aborts the command before a single
# statement can be sent. Credentials are read from the environment
# (PGPASSWORD for the psql/Flyway path) and are never echoed.
#
# Usage:
#   migrate-disposable-postgres.sh <host> <port> <database> <user>
# Environment:
#   PGPASSWORD   password for <user> (required; never printed)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/postgres-target-guard.sh"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
BACKEND="$REPO_ROOT/backend"

if [ $# -ne 4 ]; then
  echo "usage: $0 <host> <port> <database> <user>" >&2
  exit 2
fi
HOST="$1"; PORT="$2"; DB="$3"; USER_="$4"

if [ -z "${PGPASSWORD:-}" ]; then
  echo "migrate-disposable-postgres: PGPASSWORD must be set in the environment (never a default)" >&2
  exit 2
fi

# Fail-closed authority: refuse before any mutation on any unsafe target.
"$GUARD" check "$HOST" "$DB" || exit 1

# Fail closed when the postgres client tooling is absent.
command -v psql >/dev/null 2>&1 || { echo "psql not found" >&2; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "mvn not found" >&2; exit 2; }

# Reachability probe (select 1) before handing the target to Flyway.
export PGPASSWORD
if ! psql -h "$HOST" -p "$PORT" -U "$USER_" -d "$DB" -v ON_ERROR_STOP=1 -c "select 1" >/dev/null 2>&1; then
  echo "migrate-disposable-postgres: target is not reachable; refusing to continue" >&2
  exit 1
fi

echo "migrate-disposable-postgres: applying shipped migrations to disposable target $HOST/$DB"
cd "$BACKEND" || exit 2
exec mvn -q flyway:migrate \
  -Dflyway.url="jdbc:postgresql://$HOST:$PORT/$DB" \
  -Dflyway.user="$USER_" \
  -Dflyway.password="$PGPASSWORD" \
  -Dflyway.locations=classpath:db/migration
