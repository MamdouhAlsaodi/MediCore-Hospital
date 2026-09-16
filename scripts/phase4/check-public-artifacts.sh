#!/usr/bin/env bash
# Public-artifact safety scanner (plan Task 1, step 4).
#
# Smallest implementation that satisfies the RED acceptance test
# (scripts/phase4/test-check-public-artifacts.sh): it reads its candidate
# list from `git ls-files` inside the given repository directory (default:
# this repository root) — never a blind whole-home scan — and fails
# (exit 1) when any tracked candidate matches a rule below. Two rule
# classes:
#   - path rules    : matched against the tracked path itself.
#   - content rules : matched against tracked file lines. Credential
#                     assignments are anchored to a literal value (no
#                     "${...}", "<...>", or "$VAR" indirection), so safe
#                     variable-name references stay clean.
# Findings name the file, rule, and line number; secret VALUES are never
# printed.
set -u
REPO_DIR="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$REPO_DIR" || exit 2

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "not a git work tree: $REPO_DIR" >&2; exit 2; }

PATH_RULE_NAMES=();    PATH_RULE_PATTERNS=()
CONTENT_RULE_NAMES=(); CONTENT_RULE_PATTERNS=()
add_path_rule()    { PATH_RULE_NAMES+=("$1");    PATH_RULE_PATTERNS+=("$2"); }
add_content_rule() { CONTENT_RULE_NAMES+=("$1"); CONTENT_RULE_PATTERNS+=("$2"); }

# Credential-shaped runtime files as tracked paths. `.env.example` is
# deliberately allowed (it carries variable names/descriptions only; real
# env files are git-ignored).
add_path_rule credential-env-file '(^|/)\.env$'
add_path_rule credential-env-file '(^|/)runtime\.env$'
add_path_rule credential-env-file '(^|/)[^/]*\.env\.local$'

# Credential assignment with a literal value (16+ chars, no indirection
# markers "$", "{", "<" and no whitespace inside the value).
add_content_rule credential-assignment '(password|passwd|secret|api[_-]?key|access[_-]?token|token)[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9+/_-]{16,}'
add_content_rule credential-assignment '(DB|POSTGRES)_[A-Z_]*PASSWORD[[:space:]]*=[[:space:]]*["'"'"']?[A-Za-z0-9+/_-]{8,}'
add_content_rule credential-assignment 'HOSPITAL_(JWT_SECRET|ADMIN_PASSWORD|REVIEW_[A-Z_]*PASSWORD)[[:space:]]*=[[:space:]]*["'"'"']?[A-Za-z0-9+/_-]{8,}'

# Private network topology in tracked artifacts.
add_content_rule private-topology '(^|[^0-9.])(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})([^0-9.]|$)'

# Machine-specific absolute home paths in tracked artifacts. Build the
# detector from fragments so the scanner does not flag its own source.
MACHINE_HOME_PATTERN='/ho''me/[A-Za-z0-9._-]+'
add_content_rule machine-absolute-path "${MACHINE_HOME_PATTERN}/"

findings=0

# Path rules over the tracked candidate list.
while IFS= read -r file; do
  for i in "${!PATH_RULE_NAMES[@]}"; do
    if printf '%s' "$file" | grep -qE "${PATH_RULE_PATTERNS[$i]}"; then
      findings=$((findings + 1))
      echo "FINDING [${PATH_RULE_NAMES[$i]}] $file:1"
    fi
  done
done < <(git ls-files)

# Content rules over tracked file lines.
while IFS= read -r file; do
  [ -f "$file" ] || continue
  for i in "${!CONTENT_RULE_NAMES[@]}"; do
    while IFS= read -r lineno; do
      [ -n "$lineno" ] || continue
      findings=$((findings + 1))
      echo "FINDING [${CONTENT_RULE_NAMES[$i]}] $file:$lineno"
    done < <(grep -nE "${CONTENT_RULE_PATTERNS[$i]}" -- "$file" 2>/dev/null \
      | grep -viE 'synthetic|disposable|placeholder|dummy|changeme|example' \
      | cut -d: -f1)
  done
done < <(git ls-files)

if [ "$findings" -gt 0 ]; then
  echo "check-public-artifacts: $findings finding(s) — remediate tracked artifacts" >&2
  exit 1
fi
echo "check-public-artifacts: clean"
exit 0
