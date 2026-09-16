#!/usr/bin/env bash
# OpenAPI + typed-client drift check (plan Task 10, T074; FR-016).
#
# Boots the backend once in a fully isolated throwaway container (see
# lib-openapi.sh: process-local CSPRNG credentials, in-memory process-local
# database, loopback-only), regenerates BOTH artifacts into a temp directory,
# and compares them byte-for-byte against the tracked copies:
#
#   api/openapi/medicore-v1.yaml
#   frontend/src/generated/api/
#
# Any backend route/DTO/annotation drift — or any hand edit of the generated
# artifacts — fails this check with a concrete diff. Exit 0 only when the
# tracked artifacts are exactly what the served contract regenerates.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-openapi.sh
. "$SCRIPT_DIR/lib-openapi.sh"

ARTIFACT="$OPENAPI_REPO_ROOT/api/openapi/medicore-v1.yaml"
CLIENT_DIR="$OPENAPI_REPO_ROOT/frontend/src/generated/api"
TMP="$(mktemp -d)"
CONTAINER=""

cleanup() {
    [ -n "$CONTAINER" ] && openapi_stop_backend "$CONTAINER"
    rm -rf "$TMP"
}
trap cleanup EXIT

if [ ! -f "$ARTIFACT" ]; then
    echo "check-openapi-drift: tracked artifact missing: $ARTIFACT" >&2
    exit 1
fi
if [ ! -d "$CLIENT_DIR" ]; then
    echo "check-openapi-drift: tracked generated client missing: $CLIENT_DIR" >&2
    exit 1
fi

openapi_ensure_jar

booted=0
for attempt in 1 2; do
    PORT="$(openapi_free_port)"
    CONTAINER="medicore-openapi-drift-$$-$RANDOM-$attempt"
    echo "lib-openapi: boot attempt $attempt (container $CONTAINER, port $PORT)" >&2
    if TOKEN="$(openapi_boot "$CONTAINER" "$PORT")"; then
        booted=1
        break
    fi
    openapi_stop_backend "$CONTAINER"
done
if [ "$booted" -ne 1 ]; then
    echo "check-openapi-drift: isolated backend failed to start twice; failing" >&2
    exit 1
fi

openapi_fetch_docs "$PORT" "$TOKEN" "$TMP"
unset TOKEN

# Regenerate the client from the LIVE document into the temp dir only.
node "$SCRIPT_DIR/generate-openapi-client.mjs" "$TMP/api-docs.json" "$TMP/client" >/dev/null

status=0

if ! cmp -s "$TMP/api-docs.yaml" "$ARTIFACT"; then
    echo "check-openapi-drift: DRIFT in api/openapi/medicore-v1.yaml (tracked vs served contract):" >&2
    diff -u "$ARTIFACT" "$TMP/api-docs.yaml" | head -60 >&2 || true
    status=1
fi

if ! diff -rq "$CLIENT_DIR" "$TMP/client" >/dev/null; then
    echo "check-openapi-drift: DRIFT in frontend/src/generated/api (tracked vs regenerated client):" >&2
    diff -rq "$CLIENT_DIR" "$TMP/client" >&2 || true
    for f in "$(diff -rq "$CLIENT_DIR" "$TMP/client" | sed -n 's/^Files .* and \(.*\) differ$/\1/p' | head -1)"; do
        [ -n "$f" ] && diff -u "$CLIENT_DIR/$(basename "$f")" "$f" | head -40 >&2 || true
    done
    status=1
fi

if [ "$status" -ne 0 ]; then
    echo "check-openapi-drift: FAILED — regenerate with scripts/phase4/generate-openapi.sh" >&2
    exit 1
fi
echo "check-openapi-drift: no drift — tracked artifacts match the served contract"
