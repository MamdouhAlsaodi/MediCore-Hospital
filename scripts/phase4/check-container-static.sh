#!/usr/bin/env bash
# Phase 4 Task 6 (T037): static/config checks for the container review stack.
# Fails closed BEFORE any build/run when a required property is missing:
#   - multi-stage builds (>=2 FROM stages) with pinned major image tags
#   - non-root runtime (USER directive or the unprivileged nginx base)
#   - no secret-looking files copied into any image
#   - Compose ordering: postgres health -> backend health -> frontend
#   - loopback-only published review ports; no literal credential values
#   - nginx SPA fallback + same-origin /api proxy + security headers
# Output names only the violated rule; never prints file contents that could
# carry secrets. Exit 0 only when every check passes.
set -u
cd "$(dirname "$0")/../.."

fail=0
say() { printf 'FAIL: %s\n' "$1"; fail=1; }

require_file() { [ -f "$1" ] || say "missing required file: $1"; }

require_file backend/Dockerfile
require_file frontend/Dockerfile
require_file frontend/nginx.conf
require_file compose.yaml
require_file .dockerignore
require_file .env.example

stage_count() { grep -c '^FROM ' "$1" 2>/dev/null || true; }

if [ -f backend/Dockerfile ]; then
    [ "$(stage_count backend/Dockerfile)" -ge 2 ] || say "backend/Dockerfile must be a multi-stage build (>=2 FROM stages)"
    grep -q '^USER ' backend/Dockerfile || say "backend/Dockerfile runtime stage must switch to a non-root USER"
    grep -Eq '^FROM .*:(21-|21$)' backend/Dockerfile || say "backend/Dockerfile must pin the Java 21 major tag"
    grep -Eq 'COPY .*\.(env|pem|key|p12|pfx)( |$|")' backend/Dockerfile && say "backend/Dockerfile must not copy credential-shaped files"
fi

if [ -f frontend/Dockerfile ]; then
    [ "$(stage_count frontend/Dockerfile)" -ge 2 ] || say "frontend/Dockerfile must be a multi-stage build (>=2 FROM stages)"
    grep -Eq 'nginx-unprivileged|^USER ' frontend/Dockerfile || say "frontend/Dockerfile runtime must be non-root (unprivileged nginx base or USER)"
    grep -Eq '^FROM [^:]*nginx[a-z-]*:[0-9]+\.[0-9]+' frontend/Dockerfile || say "frontend/Dockerfile must pin the nginx major.minor tag"
    grep -Eq 'COPY .*\.(env|pem|key|p12|pfx)( |$|")' frontend/Dockerfile && say "frontend/Dockerfile must not copy credential-shaped files"
fi

if [ -f compose.yaml ]; then
    grep -q 'healthcheck:' compose.yaml || say "compose.yaml must define healthchecks (postgres at minimum)"
    grep -Eq 'postgres:[0-9]+' compose.yaml || say "compose.yaml must pin the postgres major image tag"
    grep -Eq 'condition: service_healthy' compose.yaml || say "compose.yaml must gate service starts on service_healthy"
    grep -Eq '127\.0\.0\.1:[^:]+:' compose.yaml || say "compose.yaml must publish review ports to loopback only"
    grep -Eq 'ports:' compose.yaml && if grep -A2 'ports:' compose.yaml | grep -Eq '^[[:space:]]*-[[:space:]]*"?[0-9]+:' ; then
        say "compose.yaml published ports must all bind to loopback (127.0.0.1)"
    fi
    # Literal (non-interpolated) credential values are forbidden; env var
    # references and required-variable syntax are the only accepted shapes.
    if grep -Eq '(_PASSWORD|_SECRET):[[:space:]]*"[^$]' compose.yaml || grep -Eq '(_PASSWORD|_SECRET):[[:space:]]*[A-Za-z0-9]' compose.yaml; then
        say "compose.yaml must reference runtime env vars for every password/secret (no literal values)"
    fi
fi

if [ -f frontend/nginx.conf ]; then
    grep -q 'try_files' frontend/nginx.conf || say "nginx.conf must provide the SPA fallback (try_files)"
    grep -q 'location /api' frontend/nginx.conf || say "nginx.conf must route /api same-origin to the backend"
    grep -q 'X-Content-Type-Options' frontend/nginx.conf || say "nginx.conf must set security headers"
    grep -q 'X-Frame-Options' frontend/nginx.conf || say "nginx.conf must set X-Frame-Options"
fi

if [ "$fail" -eq 0 ]; then
    echo "PASS: container static checks"
fi
exit "$fail"
