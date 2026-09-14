#!/usr/bin/env bash
# Phase 4 Task 6 (T042): wait for the Compose review stack to become truly
# healthy within a bounded timeout, with truthful diagnostics. Never prints
# environment values or credentials. Exits 0 only when BOTH the backend
# readiness endpoint and the frontend SPA answer over the loopback review
# ports; any timeout exits non-zero naming what never became ready.
set -u

TIMEOUT_SECONDS="${1:-300}"
BACKEND_URL="${REVIEW_BACKEND_URL:-http://127.0.0.1:${REVIEW_API_HOST_PORT:-5501}}"
FRONTEND_URL="${REVIEW_FRONTEND_URL:-http://127.0.0.1:${REVIEW_WEB_HOST_PORT:-5502}}"

case "$TIMEOUT_SECONDS" in
    ''|*[!0-9]*) echo "FAIL: timeout must be a positive integer (seconds)"; exit 2 ;;
esac

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
backend_ok=0
frontend_ok=0

while [ "$(date +%s)" -lt "$deadline" ]; do
    if [ "$backend_ok" -eq 0 ]; then
        if curl -fsS -o /dev/null "$BACKEND_URL/actuator/health/readiness" 2>/dev/null; then
            backend_ok=1
            echo "backend readiness: UP ($BACKEND_URL/actuator/health/readiness)"
        fi
    fi
    if [ "$frontend_ok" -eq 0 ]; then
        if curl -fsS -o /dev/null "$FRONTEND_URL/" 2>/dev/null; then
            frontend_ok=1
            echo "frontend SPA: UP ($FRONTEND_URL/)"
        fi
    fi
    [ "$backend_ok" -eq 1 ] && [ "$frontend_ok" -eq 1 ] && { echo "PASS: review stack healthy"; exit 0; }
    sleep 3
done

[ "$backend_ok" -eq 0 ] && echo "FAIL: backend readiness never became UP within ${TIMEOUT_SECONDS}s"
[ "$frontend_ok" -eq 0 ] && echo "FAIL: frontend SPA never became UP within ${TIMEOUT_SECONDS}s"
exit 1
