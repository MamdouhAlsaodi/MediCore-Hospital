#!/usr/bin/env bash
# RED acceptance test for scripts/phase4/check-public-artifacts.sh (plan Task 1, step 4).
# Builds a throwaway git repository with known public-safety fixtures and asserts:
#   1. credential-like runtime files are rejected,
#   2. private topology / machine-specific absolute paths in tracked docs are rejected,
#   3. clean synthetic content passes,
#   4. the scanner reports findings without printing the secret values themselves.
# Exits 0 only when every assertion holds.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-public-artifacts.sh"
fail() { echo "FAIL: $1" >&2; exit 1; }
pass() { echo "ok: $1"; }

[ -x "$CHECK" ] || fail "check-public-artifacts.sh missing or not executable"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git -C "$WORK" init -q
git -C "$WORK" config user.email t@example.invalid
git -C "$WORK" config user.name t

# Fixture 1: credential-like runtime file (tracked candidate). Values are
# assembled at runtime so this test does not persist credential-shaped literals.
CREDENTIAL_ONE="$(printf '%s' 'super-' 'secret-' 'value-' '123')"
CREDENTIAL_TWO="$(printf '%s' 'another-' 'secret-' 'value-' '456')"
printf 'DB_%s=%s\nHOSPITAL_JWT_%s=%s\n' \
  'PASSWORD' "$CREDENTIAL_ONE" 'SECRET' "$CREDENTIAL_TWO" > "$WORK/runtime.env"

# Fixture 2: tracked doc carrying private topology and a machine home path.
PRIVATE_HOST_ONE="$(printf '%s' '192.' '168.' '10.' '44')"
PRIVATE_HOST_TWO="$(printf '%s' '10.' '0.' '0.' '7')"
MACHINE_HOME="$(printf '/%s/%s/%s' 'home' 'someuser' 'secrets/history.txt')"
printf 'Review deploy target: %s and %s (private topology)\nLogs live under %s\n' \
  "$PRIVATE_HOST_ONE" "$PRIVATE_HOST_TWO" "$MACHINE_HOME" > "$WORK/notes.md"

# Fixture 3: clean synthetic file.
cat > "$WORK/clean.md" <<'EOF'
Synthetic training review only. Demo branch DEMO-BR-001. No real data.
EOF

git -C "$WORK" add runtime.env notes.md clean.md
git -C "$WORK" commit -qm fixtures

OUT="$("$CHECK" "$WORK" 2>&1)"
RC=$?
[ $RC -ne 0 ] || fail "scanner exited 0 on credential/private-topology fixtures"
pass "scanner rejects unsafe tracked candidates (exit $RC)"

echo "$OUT" | grep -q "runtime.env" || fail "scanner did not name runtime.env"
echo "$OUT" | grep -q "notes.md" || fail "scanner did not name notes.md"
pass "scanner names the offending paths"

if printf '%s' "$OUT" | grep -Fq "$CREDENTIAL_ONE" || \
   printf '%s' "$OUT" | grep -Fq "$CREDENTIAL_TWO"; then
  fail "scanner echoed a secret value into its output"
fi
pass "scanner output does not echo secret values"

# Fixture 4: same repo without the unsafe files must pass.
git -C "$WORK" rm -q runtime.env notes.md
git -C "$WORK" commit -qm cleanup
OUT2="$("$CHECK" "$WORK" 2>&1)"
RC2=$?
[ $RC2 -eq 0 ] || fail "scanner exited $RC2 on the clean repo: $OUT2"
pass "scanner passes a clean tracked tree"

# Untracked-only junk must not be flagged (tracked-candidate input only).
echo "DB_PASSWORD=x" > "$WORK/untracked.env"
OUT3="$("$CHECK" "$WORK" 2>&1)"
[ $? -eq 0 ] || fail "scanner flagged an untracked file"
pass "scanner evaluates tracked candidates only"

echo "PASS: check-public-artifacts RED acceptance"
