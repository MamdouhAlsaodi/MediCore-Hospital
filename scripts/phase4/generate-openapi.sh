#!/usr/bin/env bash
# Deterministic OpenAPI contract generation (plan Task 10, T069; FR-016).
#
# Boots the backend ONCE in a fully isolated throwaway container
# (process-local synthetic credentials from the OS CSPRNG, in-memory
# process-local database, loopback-only binding — see lib-openapi.sh) and
# writes BOTH generated artifacts:
#
#   api/openapi/medicore-v1.yaml      the served /v3/api-docs.yaml, byte-for-byte
#   frontend/src/generated/api/*.ts   the deterministic typed TypeScript client
#
# The artifacts are read-only outputs: never hand-edit them; regenerate.
# Retry contract: on a disposable-container failure the boot is retried once
# with a fresh container name and port before the script fails.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-openapi.sh
. "$SCRIPT_DIR/lib-openapi.sh"

ARTIFACT_DIR="$OPENAPI_REPO_ROOT/api/openapi"
ARTIFACT="$ARTIFACT_DIR/medicore-v1.yaml"
CLIENT_DIR="$OPENAPI_REPO_ROOT/frontend/src/generated/api"
TMP="$(mktemp -d)"
CONTAINER=""

cleanup() {
    [ -n "$CONTAINER" ] && openapi_stop_backend "$CONTAINER"
    rm -rf "$TMP"
}
trap cleanup EXIT

openapi_ensure_jar

booted=0
for attempt in 1 2; do
    PORT="$(openapi_free_port)"
    CONTAINER="medicore-openapi-gen-$$-$RANDOM-$attempt"
    echo "lib-openapi: boot attempt $attempt (container $CONTAINER, port $PORT)" >&2
    if TOKEN="$(openapi_boot "$CONTAINER" "$PORT")"; then
        booted=1
        break
    fi
    openapi_stop_backend "$CONTAINER"
done
if [ "$booted" -ne 1 ]; then
    echo "generate-openapi: isolated backend failed to start twice; failing" >&2
    exit 1
fi

openapi_fetch_docs "$PORT" "$TOKEN" "$TMP"
unset TOKEN

# Sanity: the document must be the expected generated contract.
grep -q '^openapi: 3\.1\.0$' "$TMP/api-docs.yaml" \
    || { echo "generate-openapi: unexpected document (missing openapi: 3.1.0 header)" >&2; exit 1; }
node -e "const d=require('$TMP/api-docs.json'); if(!d.paths||!d.components||!d.components.schemas) process.exit(1);"

# Write the tracked YAML artifact atomically.
mkdir -p "$ARTIFACT_DIR"
install -m 644 "$TMP/api-docs.yaml" "$ARTIFACT"

# Generate the typed client into a staging dir, then replace the tracked dir.
node "$SCRIPT_DIR/generate-openapi-client.mjs" "$TMP/api-docs.json" "$TMP/client"
mkdir -p "$CLIENT_DIR"
find "$CLIENT_DIR" -maxdepth 1 -type f -name '*.ts' -delete
install -m 644 "$TMP/client"/*.ts "$CLIENT_DIR"/

echo "generate-openapi: artifacts written"
sha256sum "$ARTIFACT" "$CLIENT_DIR"/*.ts
