#!/usr/bin/env bash
# Phase 5 public-artifact safety guard (tasks.md T010).
#
# Scans an EXACT, deterministic candidate set — by default every file under
# specs/005-enterprise-hospital-network-operations/ plus this script itself,
# optionally replaced by explicit candidate arguments — and fails closed
# (exit 1) when any candidate matches a rule below. Exit 2 is reserved for
# "cannot establish the candidate set" (not a git work tree, default
# candidate root missing) so a broken scan can never masquerade as clean.
#
# Rule categories:
#   protected-env-path      a candidate file IS a protected runtime env file
#   protected-env-reference a line references a protected runtime env path
#   credential-assignment   literal credential/password/token assignments
#   private-key-content     embedded private key material
#   private-topology        private network address literals
#   machine-absolute-path   machine-specific absolute home/root paths
#   unsupported-claim       clinical/production/SaaS/compliance claims
#   missing-candidate       an explicit candidate argument does not exist
#
# Suppression model (deterministic, documented):
#   credential-assignment and private-key-content are suppressed ONLY for
#   placeholder-shaped values (changeme/example/...) or "${...}"/"<...>"
#   indirection; boundary words never suppress them.
#   protected-env-reference, private-topology, machine-absolute-path, and
#   unsupported-claim are suppressed on lines that are documented boundary
#   statements — the line itself, or one of the 6 lines above it, must match
#   the boundary pattern (never / must not / out of scope / synthetic /
#   non-clinical / training / portfolio / no / not / claims / promises ...).
#   This lets prohibition sections list rejected terms while still flagging
#   assertion-shaped violations.
# Findings print CATEGORY, PATH, and LINE NUMBER only — never matched values.
#
# Usage:
#   check-public-artifacts.sh                       scan the default Phase 5 candidate set
#   check-public-artifacts.sh PATH [PATH ...]       scan exactly these candidates
#   check-public-artifacts.sh --self-test           embedded negative/positive proof
set -u
export LC_ALL=C

SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
REPO_ROOT="$(cd "$(dirname "$SCRIPT_PATH")/../.." && pwd)"
SPEC_DIR="$REPO_ROOT/specs/005-enterprise-hospital-network-operations"

findings=0

report() { # category, path, line — values are never printed
  findings=$((findings + 1))
  printf 'FINDING [%s] %s:%s\n' "$1" "$2" "$3"
}

# Patterns are assembled from quoted fragments so this script's own source
# lines never contain a full matchable literal (the script is itself a
# candidate and must stay clean under its own rules).
ENV1='runtime''\.env'
ENV2='trial''\.env'
CONF='\.config/medicore'
MRUNTIME='medicore-runtime'
HOME_PAT='/ho''me/[A-Za-z0-9._-]+/'
ROOT_PAT='/(ro''ot|Us''ers)/[A-Za-z0-9._-]+/'
PRIVTOP='(^|[^0-9.])(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3})([^0-9.]|$)'
CRED1='(password|passwd|secret|api[_-]?key|access[_-]?token|token)[[:space:]]*[:=][[:space:]]*["'"'"']?[-A-Za-z0-9_.=+/]{16,}'
CRED2='(DB|POSTGRES|SPRING_DATASOURCE|HOSPITAL)_[A-Z_]*(PASSWORD|SECRET|TOKEN)[[:space:]]*=[[:space:]]*["'"'"']?[A-Za-z0-9+/_-]{8,}'
CRED3="[Bb]earer[[:space:]]+[-A-Za-z0-9_.=+/]{20,}"
CRED4='(gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[baprs][-][A-Za-z0-9-]{10,}|sk[-][A-Za-z0-9]{20,})'
PRIVKEY='-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVA''TE KEY-----'
# Unsupported claims (suppressed only in boundary context).
CLAIM1='pro''duction[- ](ready|grade|use|deployment|deployed|hardened)'
CLAIM2='clin''ical(ly)?[- ](validated|approved|certified|proven|grade|supported)'
CLAIM3='\b(HIP''AA|GDP''R|LGP''D|HL''7|FHI''R|DICO''M|SNOM''ED|ICD-?10|CE[- ]marked)\b'
CLAIM4='\b(SL''A|SL''O|RP''O|RT''O)\b'
CLAIM5='\bS''aaS\b'
CLAIM6='\b(certified|certif''ication|accredited|accreditation|compliant|compliance)\b'
# Boundary/negation context: a documented safe-boundary line, e.g. plan
# prohibitions, out-of-scope lists, synthetic/non-clinical limits.
BOUNDARY='never|must not|must stop|stop conditions|do not|don'"'"'t|does not|doesn'"'"'t|cannot|forbid|refus|exclud|defer|avoid|without|out of scope|synthetic|non-clinical|training|portfolio|no saas|\bno\b|\bnot\b|\bnor\b|claims?|promis|placeholder|example|illustrative|disposable'

boundary_line() { # 0 = boundary context, 1 = not (grep errors count as NOT boundary: fail closed)
  local rc=0
  printf '%s' "$1" | grep -iqE -e "$BOUNDARY" || rc=$?
  [ "$rc" -eq 0 ] && return 0
  return 1
}

# matched PATTERN LINE -> 0 match, 1 no match, 2 grep error (callers must
# treat 2 as a scan-error finding so a broken pattern can never fail open).
matched() {
  local rc=0
  printf '%s\n' "$2" | grep -iqE -e "$1" || rc=$?
  [ "$rc" -eq 0 ] && return 0
  [ "$rc" -eq 1 ] && return 1
  return 2
}

scan_path_rules() {
  local file="$1"
  case "$file" in
    .env|*/.env|.env.*|*/.env.*|*.env)
      [ "$(basename "$file")" = ".env.example" ] && return 0
      report protected-env-path "$file" 1
      return 0 ;;
  esac
  case "$file" in
    *.key|*.pem|*.p12|*.pfx|*.crt|*.cer)
      report private-key-content "$file" 1 ;;
  esac
}

# Content scan with a 6-line upward boundary-context window.
scan_content_rules() {
  local file="$1"
  [ -f "$file" ] || return 0
  local raw=() nums=() contents=() k j suppressed
  mapfile -t raw < <(grep -anE '.' "$file" 2>/dev/null) || return 0
  for k in "${!raw[@]}"; do
    nums[${#nums[@]}]="${raw[$k]%%:*}"
    contents[${#contents[@]}]="${raw[$k]#*:}"
  done
  in_boundary_context() { # index into nums/contents
    local idx="$1"
    boundary_line "${contents[$idx]}" && return 0
    for ((j = idx - 1; j >= 0 && nums[idx] - nums[j] <= 6; j--)); do
      boundary_line "${contents[$j]}" && return 0
    done
    return 1
  }
  for k in "${!contents[@]}"; do
    local line="${contents[$k]}" lineno="${nums[$k]}" mrc
    # credential-assignment / private-key-content: no boundary suppression.
    matched "$CRED1|$CRED2|$CRED3|$CRED4" "$line"; mrc=$?
    if [ "$mrc" -eq 2 ]; then
      report scan-error "$file" "$lineno"
      continue
    fi
    if [ "$mrc" -eq 0 ]; then
      if ! printf '%s' "$line" | grep -qE '(\$\{|<[^>]*>|^[[:space:]]*#|changeme|CHANGEME|placeholder|PLACEHOLDER|dummy|DUMMY|example|EXAMPLE)'; then
        report credential-assignment "$file" "$lineno"
        continue
      fi
    fi
    matched "$PRIVKEY" "$line"; mrc=$?
    if [ "$mrc" -eq 2 ]; then
      report scan-error "$file" "$lineno"
      continue
    fi
    if [ "$mrc" -eq 0 ]; then
      if ! printf '%s' "$line" | grep -qiE '(example|placeholder|dummy|illustrative)'; then
        report private-key-content "$file" "$lineno"
        continue
      fi
    fi
    suppressed=0
    in_boundary_context "$k" && suppressed=1
    [ "$suppressed" -eq 1 ] && continue
    # Every category is reported for a violating line (a line may leak more
    # than one kind of value); nothing here continues past a category.
    matched "$ENV1|$ENV2|$CONF|$MRUNTIME" "$line" && report protected-env-reference "$file" "$lineno"
    matched "$PRIVTOP" "$line" && report private-topology "$file" "$lineno"
    matched "$HOME_PAT|$ROOT_PAT" "$line" && report machine-absolute-path "$file" "$lineno"
    matched "$CLAIM1|$CLAIM2|$CLAIM3|$CLAIM4|$CLAIM5|$CLAIM6" "$line" && report unsupported-claim "$file" "$lineno"
  done
}

default_candidates() {
  [ -d "$SPEC_DIR" ] || { printf 'check-public-artifacts: BLOCKED default candidate dir missing: %s\n' \
      "${SPEC_DIR#$REPO_ROOT/}" >&2; return 2; }
  git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || { echo 'check-public-artifacts: BLOCKED not a git work tree' >&2; return 2; }
  {
    find "$SPEC_DIR" -type f
    printf '%s\n' "$SCRIPT_PATH"
  } | sed "s|^$REPO_ROOT/||" | sort
}

run_scan() {
  local candidates=("$@") c missing=0 sorted rel
  for c in "${candidates[@]}"; do
    if [ ! -f "$REPO_ROOT/$c" ] && [ ! -f "$c" ]; then
      report missing-candidate "$c" 1
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    printf 'check-public-artifacts: missing explicit candidate(s) — fail closed\n' >&2
    return 1
  fi
  sorted="$(
    for c in "${candidates[@]}"; do
      if [ -f "$REPO_ROOT/$c" ]; then printf '%s\n' "$c"; else printf '%s\n' "${c#$REPO_ROOT/}"; fi
    done | sort -u
  )"
  # Here-string (not a pipeline) so `findings` stays in this shell.
  while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    scan_path_rules "$rel"
    scan_content_rules "$rel"
  done <<< "$sorted"
  if [ "$findings" -gt 0 ]; then
    printf 'check-public-artifacts: %s finding(s) — remediate candidate artifacts\n' "$findings" >&2
    return 1
  fi
  echo 'check-public-artifacts: clean'
  return 0
}

# ---------------------------------------------------------------------------
# Embedded self-test: proves detection of every category AND that documented
# safe boundaries pass. Planted violation strings are built from fragments so
# this source file never contains a matchable literal.
# ---------------------------------------------------------------------------
self_test() {
  STDIR="$(mktemp -d /tmp/phase5_guard_selftest.XXXXXX)" || exit 2
  trap 'rm -rf "$STDIR"' EXIT INT TERM
  local tmp="$STDIR"

  # Plant: protected env file candidate.
  : > "$tmp/run""time.env"
  # Plant: credential assignment (literal value, no indirection).
  printf '%s\n' "PA"'SSWORD=s3cr3tv4lue1234567890' > "$tmp/cred.txt"
  # Plant: private key header.
  printf '%s\n' '-----BEGIN RSA PR''IVATE KEY-----' > "$tmp/key.pem.txt"
  # Plant: private topology + machine path + protected env reference (raw).
  printf '%s\n' "db at 192.168.""1.50 see /ho""me/server/run""time.env" > "$tmp/leak.txt"
  # Plant: unsupported claims (raw).
  printf '%s\n' 'this system is pro''duction-ready, HIP''AA compliant S''aaS' > "$tmp/claims.txt"
  # Plant: boundary doc that lists rejected terms under an out-of-scope heading.
  cat > "$tmp/boundarydoc.md" <<'EOF'
## Explicitly Out of Scope

- SaaS multi-tenancy or independent customer organizations.
- Regulatory certification, HIPAA/LGPD/GDPR compliance claims, production capacity, SLA/SLO/RPO/RTO promises.
EOF

  local out rc
  out="$(run_scan "$tmp/cred.txt" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] || { echo "SELF-TEST FAIL: credential candidate rc=$rc: $out" >&2; return 1; }
  printf '%s' "$out" | grep -q 'credential-assignment' || { echo 'SELF-TEST FAIL: credential-assignment not detected' >&2; return 1; }

  out="$(run_scan "$tmp/runtime.env" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'protected-env-path' \
    || { echo 'SELF-TEST FAIL: protected env file not detected' >&2; return 1; }

  out="$(run_scan "$tmp/key.pem.txt" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'private-key-content' \
    || { echo 'SELF-TEST FAIL: private key not detected' >&2; return 1; }

  out="$(run_scan "$tmp/leak.txt" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] || { echo "SELF-TEST FAIL: leak candidate rc=$rc: $out" >&2; return 1; }
  printf '%s' "$out" | grep -q 'private-topology' || { echo 'SELF-TEST FAIL: private topology not detected' >&2; return 1; }
  printf '%s' "$out" | grep -q 'machine-absolute-path' || { echo 'SELF-TEST FAIL: machine path not detected' >&2; return 1; }
  printf '%s' "$out" | grep -q 'protected-env-reference' || { echo 'SELF-TEST FAIL: protected env reference not detected' >&2; return 1; }

  out="$(run_scan "$tmp/claims.txt" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'unsupported-claim' \
    || { echo "SELF-TEST FAIL: unsupported claim not detected: $out" >&2; return 1; }

  out="$(run_scan "$tmp/boundarydoc.md" 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] || { echo "SELF-TEST FAIL: safe boundary doc flagged: $out" >&2; return 1; }

  # Values must never be printed: findings show path:line only.
  out="$(run_scan "$tmp/cred.txt" "$tmp/leak.txt" "$tmp/claims.txt" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] || { echo 'SELF-TEST FAIL: mixed scan rc='$rc >&2; return 1; }
  printf '%s' "$out" | grep -q 's3cr3tv4lue' && { echo 'SELF-TEST FAIL: secret value leaked to output' >&2; return 1; }
  printf '%s' "$out" | grep -qE '192\.168|/home/' && { echo 'SELF-TEST FAIL: topology/path value leaked to output' >&2; return 1; }

  # Missing explicit candidate must fail closed.
  out="$(run_scan "$tmp/does-not-exist.md" 2>&1)"; rc=$?
  [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'missing-candidate' \
    || { echo 'SELF-TEST FAIL: missing candidate did not fail closed' >&2; return 1; }

  echo 'check-public-artifacts: self-test ok (all categories detected, boundaries safe, values never printed)'
  return 0
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  -h|--help) sed -n '2,38p' "$SCRIPT_PATH" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

if [ "$#" -gt 0 ]; then
  run_scan "$@"
else
  cands="$(default_candidates)" || exit 2
  mapfile -t CANDIDATES <<< "$cands"
  run_scan "${CANDIDATES[@]}"
fi
exit $?
