#!/usr/bin/env bash
# smoke-patient-journey.sh — repeatable local performance smoke for the
# demonstrated patient journey (docs/plan1.md Task 12).
#
# Scope: Training/Portfolio evidence only. This script makes NO production
# capacity claim; it measures the demonstrated workflow against a local,
# synthetic-data backend so regressions become visible.
#
# The script boots nothing itself. Start the backend first (docs/runbook.md):
#
#   cd backend
#   MEDICORE_DEMO_SEED=true \
#   HOSPITAL_ADMIN_PASSWORD="<disposable local value, >=12 chars>" \
#   HOSPITAL_JWT_SECRET="<disposable local value>" \
#   mvn spring-boot:run
#
# Environment:
#   BASE_URL                 backend base URL (default http://127.0.0.1:5501)
#   RUNS                     repetitions of the journey (default 3)
#   HOSPITAL_SMOKE_USERNAME  smoke login username (default admin)
#   HOSPITAL_SMOKE_PASSWORD  REQUIRED; the login password. For the local smoke
#                            backend this is the same disposable value supplied
#                            as HOSPITAL_ADMIN_PASSWORD at startup. Never a
#                            real or shared secret; never hardcoded here.
#
# Steps per run (each timed, ms): login -> create synthetic patient
# (MRN SMOKE-<timestamp>-<run>) -> patient search (GET /api/patients?q=<name>;
# the patient id is taken from the search result) -> patient detail ->
# appointment create (POST /api/appointments with the created patient and a
# seeded professional) -> appointment list -> dashboard.
#
# Failure contract: exits non-zero on non-2xx responses, missing JSON contract
# fields (login accessToken/roles, patient/appointment id, list arrays,
# dashboard count keys), or an unresolvable workflow reference. If the staff
# reference cannot resolve it FAILS by design (the workflow is the point).
#
# Data: every created record is synthetic (SMOKE- MRNs, synthetic names,
# synthetic.example.test emails). Nothing is cleaned up; run only against a
# disposable local demo database (e.g. in-memory H2), never shared data.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:5501}"
RUNS="${RUNS:-3}"
SMOKE_USER="${HOSPITAL_SMOKE_USERNAME:-admin}"

fail() { printf '[smoke] FAIL: %s\n' "$1" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "required tool '$1' not found in PATH"; }
need curl; need python3; need awk; need date

if [ -z "${HOSPITAL_SMOKE_PASSWORD:-}" ]; then
  fail "HOSPITAL_SMOKE_PASSWORD is required (the disposable login password of the local smoke backend; see header comment)"
fi

now_ms() { date +%s%3N; }

BODY_FILE="$(mktemp)"
TIMINGS="$(mktemp)"
trap 'rm -f "$BODY_FILE" "$TIMINGS"' EXIT

# http METHOD PATH [JSON_BODY] [TOKEN] -> prints HTTP status; body in BODY_FILE
http() {
  local method=$1 path=$2 body=${3:-} token=${4:-} status args
  args=(-sS --max-time 15 -X "$method" -o "$BODY_FILE" -w '%{http_code}'
        -H 'Content-Type: application/json')
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  if [ -n "$body" ];  then args+=(-d "$body"); fi
  if ! status=$(curl "${args[@]}" "$BASE_URL$path"); then
    fail "request $method $path could not reach $BASE_URL"
  fi
  printf '%s' "$status"
}

# jsonq JSON EXPR -> value (python3 stdlib; no jq dependency)
jsonq() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {"d": d}))' "$2" <<<"$1" 2>/dev/null; }

expect_2xx() { # expect_2xx <status> <what>
  case "$1" in 2??) ;; *) fail "$2 returned HTTP $1 (expected 2xx)";; esac
}

step_ms() { printf '%s %s\n' "$1" "$2" >>"$TIMINGS"; }

echo "[smoke] backend: $BASE_URL  runs: $RUNS  user: $SMOKE_USER"

# ---- Preflight: backend must already be up (permitAll health endpoint) ----
status=$(http GET /actuator/health)
if [ ! "$status" = 200 ]; then
  fail "preflight /actuator/health returned HTTP $status — backend not reachable at $BASE_URL; start it first (docs/runbook.md)"
fi

# ---- Preflight login: credentials and role must be valid before anything else ----
status=$(http POST /api/auth/login "{\"username\":\"$SMOKE_USER\",\"password\":\"$HOSPITAL_SMOKE_PASSWORD\"}")
if [ ! "$status" = 200 ]; then
  fail "preflight login returned HTTP $status — check HOSPITAL_SMOKE_USERNAME/HOSPITAL_SMOKE_PASSWORD against the locally started backend"
fi
body="$(cat "$BODY_FILE")"
if ! jsonq "$body" "len([r for r in d['roles'] if r in ('ADMIN','RECEPTIONIST')])" | grep -qE '^[1-9]'; then
  fail "smoke user '$SMOKE_USER' lacks ADMIN/RECEPTIONIST role — the demonstrated journey cannot run"
fi
PREFLIGHT_TOKEN="$(jsonq "$body" "d['accessToken']")"

# ---- Reference resolution (once): a professional for appointment create ----
status=$(http GET /api/staff '' "$PREFLIGHT_TOKEN")
expect_2xx "$status" "GET /api/staff"
body="$(cat "$BODY_FILE")"
count="$(jsonq "$body" "len(d) if isinstance(d,list) else -1")"
if ! [ "${count:-0}" -gt 0 ] 2>/dev/null; then
  fail "staff list has no resolvable professional reference (count=${count:-unparseable}). Start the backend with MEDICORE_DEMO_SEED=true (docs/runbook.md) so DEMO-STAFF professionals exist."
fi
PROFESSIONAL_ID="$(jsonq "$body" "d[0]['id']")"
if [ -z "$PROFESSIONAL_ID" ]; then
  fail "staff list entry carries no id field — professional reference cannot resolve"
fi
echo "[smoke] professional reference resolved: $PROFESSIONAL_ID"

TS="$(date +%s)"
run_journey() {
  local run=$1 token patient_id appt_id status body ms t1 mrn when

  # 1. login — contract: {accessToken, tokenType, username, roles[]}
  t1=$(now_ms)
  status=$(http POST /api/auth/login "{\"username\":\"$SMOKE_USER\",\"password\":\"$HOSPITAL_SMOKE_PASSWORD\"}")
  ms=$(( $(now_ms) - t1 )); step_ms login "$ms"
  expect_2xx "$status" "login"
  body="$(cat "$BODY_FILE")"
  token="$(jsonq "$body" "d['accessToken']")"
  if [ -z "$token" ]; then
    fail "login response missing accessToken field (body starts: $(printf '%s' "$body" | head -c 160))"
  fi
  if ! jsonq "$body" "len(d['roles'])" | grep -qE '^[1-9]'; then
    fail "login response carries no roles array"
  fi
  if ! jsonq "$body" "len([r for r in d['roles'] if r in ('ADMIN','RECEPTIONIST')])" | grep -qE '^[1-9]'; then
    fail "smoke user '$SMOKE_USER' lacks ADMIN/RECEPTIONIST role — the demonstrated journey cannot run"
  fi

  # 2. create a distinct synthetic patient for this run
  mrn="SMOKE-${TS}-${run}"
  t1=$(now_ms)
  status=$(http POST /api/patients "{\"medicalRecordNumber\":\"$mrn\",\"fullName\":\"Smoke Patient Run $run\",\"dateOfBirth\":\"2000-01-01\",\"sex\":\"unspecified\",\"phone\":\"+10000000000\",\"email\":\"smoke-run${run}-${TS}@synthetic.example.test\",\"nationalId\":\"NID-SMOKE-${TS}-${run}\",\"address\":\"1 Synthetic Street\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_create "$ms"
  expect_2xx "$status" "patient create ($mrn)"
  body="$(cat "$BODY_FILE")"
  patient_id="$(jsonq "$body" "d['id']")"
  if [ -z "$patient_id" ]; then
    fail "created patient response missing id field"
  fi

  # 3. patient search — q matches fullName (contains, ignore-case)
  t1=$(now_ms)
  status=$(http GET "/api/patients?q=Smoke%20Patient%20Run%20$run" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_search "$ms"
  expect_2xx "$status" "patient search"
  body="$(cat "$BODY_FILE")"
  if ! jsonq "$body" "isinstance(d,list)" | grep -q True; then
    fail "patient search returned no JSON array"
  fi
  if ! [ "$(jsonq "$body" "len(d)")" -gt 0 ] 2>/dev/null; then
    fail "patient search returned an empty array for a just-created patient"
  fi
  patient_id="$(jsonq "$body" "[p['id'] for p in d if p.get('medicalRecordNumber')=='$mrn'][0]")"
  if [ -z "$patient_id" ]; then
    fail "patient search result missing the created record (mrn=$mrn)"
  fi

  # 4. patient detail
  t1=$(now_ms)
  status=$(http GET "/api/patients/$patient_id" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_detail "$ms"
  expect_2xx "$status" "patient detail"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(jsonq "$body" "d['id']")" = "$patient_id" ]; then
    fail "patient detail id mismatch"
  fi

  # 5. appointment create — references must resolve or the run FAILS (by design)
  when="$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%S)"
  t1=$(now_ms)
  status=$(http POST /api/appointments "{\"patientId\":\"$patient_id\",\"professionalId\":\"$PROFESSIONAL_ID\",\"scheduledAt\":\"$when\",\"type\":\"consultation\",\"status\":\"scheduled\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms appointment_create "$ms"
  expect_2xx "$status" "appointment create"
  body="$(cat "$BODY_FILE")"
  appt_id="$(jsonq "$body" "d['id']")"
  if [ -z "$appt_id" ]; then
    fail "created appointment response missing id field"
  fi

  # 6. appointment list — array containing the created appointment
  t1=$(now_ms)
  status=$(http GET /api/appointments '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms appointment_list "$ms"
  expect_2xx "$status" "appointment list"
  body="$(cat "$BODY_FILE")"
  if ! jsonq "$body" "isinstance(d,list)" | grep -q True; then
    fail "appointment list returned no JSON array"
  fi
  if ! jsonq "$body" "len([a for a in d if a.get('id')=='$appt_id'])" | grep -qE '^[1-9]'; then
    fail "appointment list does not contain the created appointment ($appt_id)"
  fi

  # 7. dashboard — five count keys, numeric
  t1=$(now_ms)
  status=$(http GET /api/dashboard '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms dashboard "$ms"
  expect_2xx "$status" "dashboard"
  body="$(cat "$BODY_FILE")"
  for k in patients appointments admissions emergencyVisits invoices; do
    if ! jsonq "$body" "isinstance(d.get('$k'),(int,float))" | grep -q True; then
      fail "dashboard response missing numeric '$k' key"
    fi
  done
}

total_start=$(now_ms)
for r in $(seq 1 "$RUNS"); do
  echo "[smoke] run $r/$RUNS"
  run_journey "$r"
done
total_ms=$(( $(now_ms) - total_start ))

# ---- Summary: per-step min / median / max across runs ----
echo "-----------------[ smoke timings (ms) ]-----------------"
printf '%-20s %6s %8s %8s %8s\n' step min median max
for step in login patient_create patient_search patient_detail appointment_create appointment_list dashboard; do
  read -r mn md mx < <(awk -v s="$step" '$1==s{print $2}' "$TIMINGS" | sort -n | awk '
    {v[NR]=$1} END {
      if (NR==0) {print "- - -"; exit}
      med = (NR%2) ? v[(NR+1)/2] : int((v[NR/2]+v[NR/2+1])/2)
      print v[1], med, v[NR]
    }')
  printf '%-20s %6s %8s %8s %8s\n' "$step" "$mn" "$md" "$mx"
done
echo "---------------------------------------------------------"
echo "SMOKE RESULT: PASS (runs=$RUNS, total_ms=$total_ms, base_url=$BASE_URL, user=$SMOKE_USER)"
