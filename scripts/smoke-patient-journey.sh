#!/usr/bin/env bash
# smoke-patient-journey.sh — repeatable local performance smoke for the
# demonstrated care-operations journey (docs/plan2.md Task 9, extending the
# docs/plan1.md Task 12 patient journey).
#
# Scope: Training/Portfolio evidence only. This script makes NO production
# capacity claim; it measures the demonstrated workflow against a local,
# synthetic-data backend so regressions become visible.
#
# The script boots nothing itself and cleans nothing by design. It may only
# target a disposable ephemeral/local demo database (e.g. in-memory H2),
# never shared or live data. Start the backend first (docs/runbook.md):
#
#   cd backend
#   MEDICORE_DEMO_SEED=true \
#   HOSPITAL_ADMIN_PASSWORD="<disposable local value, >=12 chars>" \
#   HOSPITAL_JWT_SECRET="<disposable local value>" \
#   mvn spring-boot:run
#
# Environment:
#   BASE_URL                 backend base URL (default loopback http://127.0.0.1:5501)
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
# seeded professional) -> appointment list -> admission create (the server
# sets status=ADMITTED) -> discharge transition (ADMITTED -> DISCHARGED, with
# a nonblank server-stamped dischargedAt) -> emergency visit create (the
# server sets status=WAITING; the triage label is a neutral 1-5 demo value
# with no clinical meaning) -> WAITING -> IN_TREATMENT -> CLOSED ->
# invoice create (FINANCIAL SIMULATION ONLY: display-only demo amount and
# currency label; per-run unique number SMOKE-INV-<timestamp>-<run>; the
# server sets status=DRAFT) -> DRAFT -> ISSUED -> PAID -> dashboard (all
# eleven numeric count keys required; totals must at least be consistent
# with the journey just executed, and because the Task 8 seed deliberately
# contains open/active/draft/issued/void fixtures, no status-aware key is
# required to be zero — only numeric and non-negative).
#
# Failure contract: exits non-zero on network failure, any non-2xx response,
# a missing/malformed JSON contract (login accessToken/roles, created record
# ids, the server-returned lifecycle status, list arrays, the eleven
# dashboard count keys), a missing id/reference, or an unexpected lifecycle
# status. Error messages never include response bodies (they can carry
# tokens). If the staff reference cannot resolve it FAILS by design (the
# workflow is the point).
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

# expect_field BODY EXPR WHAT -> prints the value; fails on missing, null, or
# malformed JSON. Never echoes the response body (it can carry tokens).
expect_field() { # <json-body> <jsonq-expr> <what>
  local value
  value="$(jsonq "$1" "$2")"
  if [ -z "$value" ] || [ "$value" = "None" ]; then
    fail "$3 missing or malformed in response JSON"
  fi
  printf '%s' "$value"
}

# expect_status BODY EXPECTED WHAT — assert the server-owned status field of
# a create/transition response equals the expected lifecycle state.
expect_status() { # <json-body> <expected> <what>
  local got
  got="$(jsonq "$1" "d.get('status')")"
  if [ ! "$got" = "$2" ]; then
    fail "$3 returned status '$got' (expected '$2')"
  fi
}

# transition_resource TOKEN ENDPOINT ID TARGET — timed server-owned status
# transition (PUT /api/<endpoint>/<id>/status); asserts 2xx and that the
# returned status equals the requested target. Leaves the response body in
# BODY_FILE for any follow-up field assertions.
transition_resource() { # <token> <endpoint> <id> <target-status>
  local token=$1 endpoint=$2 id=$3 target=$4 status body t1 ms
  t1=$(now_ms)
  status=$(http PUT "/api/$endpoint/$id/status" "{\"status\":\"$target\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms "${endpoint}_${target}" "$ms"
  expect_2xx "$status" "$endpoint transition -> $target"
  body="$(cat "$BODY_FILE")"
  expect_status "$body" "$target" "$endpoint transition -> $target"
}

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
PREFLIGHT_TOKEN="$(expect_field "$body" "d['accessToken']" "preflight login response")"

# ---- Reference resolution (once): a professional for appointment create ----
status=$(http GET /api/staff '' "$PREFLIGHT_TOKEN")
expect_2xx "$status" "GET /api/staff"
body="$(cat "$BODY_FILE")"
count="$(jsonq "$body" "len(d) if isinstance(d,list) else -1")"
if ! [ "${count:-0}" -gt 0 ] 2>/dev/null; then
  fail "staff list has no resolvable professional reference (count=${count:-unparseable}). Start the backend with MEDICORE_DEMO_SEED=true (docs/runbook.md) so DEMO-STAFF professionals exist."
fi
PROFESSIONAL_ID="$(expect_field "$body" "d[0]['id']" "staff list entry")"
echo "[smoke] professional reference resolved: $PROFESSIONAL_ID"

# ---- Journey stage: login (timed) -> prints the bearer token ----
journey_login() { # <username>
  local user=$1 status body t1 ms token
  t1=$(now_ms)
  status=$(http POST /api/auth/login "{\"username\":\"$user\",\"password\":\"$HOSPITAL_SMOKE_PASSWORD\"}")
  ms=$(( $(now_ms) - t1 )); step_ms login "$ms"
  expect_2xx "$status" "login"
  body="$(cat "$BODY_FILE")"
  token="$(expect_field "$body" "d['accessToken']" "login response")"
  if ! jsonq "$body" "len(d['roles'])" | grep -qE '^[1-9]'; then
    fail "login response carries no roles array"
  fi
  if ! jsonq "$body" "len([r for r in d['roles'] if r in ('ADMIN','RECEPTIONIST')])" | grep -qE '^[1-9]'; then
    fail "smoke user '$user' lacks ADMIN/RECEPTIONIST role — the demonstrated journey cannot run"
  fi
  printf '%s' "$token"
}

# ---- Journey stage: create the run's distinct synthetic patient ----
patient_create() { # <token> <run> <timestamp> <mrn> -> prints the created patient id
  local token=$1 run=$2 ts=$3 mrn=$4 status body t1 ms
  t1=$(now_ms)
  status=$(http POST /api/patients "{\"medicalRecordNumber\":\"$mrn\",\"fullName\":\"Smoke Patient Run $run\",\"dateOfBirth\":\"2000-01-01\",\"sex\":\"unspecified\",\"phone\":\"+10000000000\",\"email\":\"smoke-run${run}-${ts}@synthetic.example.test\",\"nationalId\":\"NID-SMOKE-${ts}-${run}\",\"address\":\"1 Synthetic Street\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_create "$ms"
  expect_2xx "$status" "patient create ($mrn)"
  body="$(cat "$BODY_FILE")"
  expect_field "$body" "d['id']" "patient create response"
}

# ---- Journey stage: search must return the record; detail must echo the id ----
patient_search_detail() { # <token> <run> <mrn> <patient_id>
  local token=$1 run=$2 mrn=$3 patient_id=$4 status body t1 ms
  t1=$(now_ms)
  status=$(http GET "/api/patients?q=Smoke%20Patient%20Run%20$run" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_search "$ms"
  expect_2xx "$status" "patient search"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(jsonq "$body" "len([p for p in d if p.get('medicalRecordNumber')=='$mrn'])")" -ge 1 ] 2>/dev/null; then
    fail "patient search did not return the just-created record (mrn=$mrn)"
  fi
  t1=$(now_ms)
  status=$(http GET "/api/patients/$patient_id" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_detail "$ms"
  expect_2xx "$status" "patient detail"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(jsonq "$body" "d['id']")" = "$patient_id" ]; then
    fail "patient detail id mismatch"
  fi
}

journey_patient() { # <token> <run> <timestamp> -> prints the created patient id
  local token=$1 run=$2 ts=$3 mrn patient_id
  mrn="SMOKE-${ts}-${run}"
  patient_id="$(patient_create "$token" "$run" "$ts" "$mrn")"
  patient_search_detail "$token" "$run" "$mrn" "$patient_id"
  printf '%s' "$patient_id"
}

# ---- Journey stage: appointment against the seeded professional; the list ----
# ---- must prove it (references must resolve or the run FAILS, by design) ----
journey_appointment() { # <token> <patient_id> <professional_id>
  local token=$1 patient_id=$2 professional_id=$3 appt_id status body when t1 ms
  when="$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%S)"
  t1=$(now_ms)
  status=$(http POST /api/appointments "{\"patientId\":\"$patient_id\",\"professionalId\":\"$professional_id\",\"scheduledAt\":\"$when\",\"type\":\"consultation\",\"status\":\"scheduled\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms appointment_create "$ms"
  expect_2xx "$status" "appointment create"
  body="$(cat "$BODY_FILE")"
  appt_id="$(expect_field "$body" "d['id']" "appointment create response")"
  t1=$(now_ms)
  status=$(http GET /api/appointments '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms appointment_list "$ms"
  expect_2xx "$status" "appointment list"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(jsonq "$body" "len([a for a in d if a.get('id')=='$appt_id'])")" -ge 1 ] 2>/dev/null; then
    fail "appointment list does not contain the created appointment"
  fi
}

# ---- Journey stage: admission (server sets ADMITTED), then the sole ----
# ---- ADMITTED -> DISCHARGED transition with a server-stamped dischargedAt ----
journey_admission() { # <token> <patient_id>
  local token=$1 patient_id=$2 admission_id status body t1 ms
  t1=$(now_ms)
  status=$(http POST /api/admissions "{\"patientId\":\"$patient_id\",\"admittedAt\":\"$(date -u '+%Y-%m-%dT%H:%M:%S')\",\"reason\":\"Demo synthetic smoke admission (no real encounter)\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms admission_create "$ms"
  expect_2xx "$status" "admission create"
  body="$(cat "$BODY_FILE")"
  admission_id="$(expect_field "$body" "d['id']" "admission create response")"
  expect_status "$body" "ADMITTED" "admission create"
  transition_resource "$token" admissions "$admission_id" DISCHARGED
  body="$(cat "$BODY_FILE")"
  expect_field "$body" "d['dischargedAt']" "discharge response dischargedAt" >/dev/null
}

# ---- Journey stage: emergency visit (server sets WAITING; the triage label ----
# ---- is a neutral 1-5 demo value with no clinical meaning), then the only ----
# ---- legal path WAITING -> IN_TREATMENT -> CLOSED ----
journey_emergency() { # <token> <patient_id>
  local token=$1 patient_id=$2 visit_id status body t1 ms
  t1=$(now_ms)
  status=$(http POST /api/emergency-visits "{\"patientId\":\"$patient_id\",\"arrivalAt\":\"$(date -u '+%Y-%m-%dT%H:%M:%S')\",\"triageLevel\":\"3\",\"chiefComplaint\":\"Demo synthetic smoke intake\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms emergency_create "$ms"
  expect_2xx "$status" "emergency visit create"
  body="$(cat "$BODY_FILE")"
  visit_id="$(expect_field "$body" "d['id']" "emergency visit create response")"
  expect_status "$body" "WAITING" "emergency visit create"
  transition_resource "$token" emergency-visits "$visit_id" IN_TREATMENT
  transition_resource "$token" emergency-visits "$visit_id" CLOSED
}

# ---- Journey stage: invoice — FINANCIAL SIMULATION ONLY (display-only demo ----
# ---- amount and currency; no payments, gateways, taxes, or real money). ----
# ---- The server sets DRAFT; then the legal path DRAFT -> ISSUED -> PAID. ----
journey_invoice() { # <token> <patient_id> <run>
  local token=$1 patient_id=$2 run=$3 invoice_id status body t1 ms
  t1=$(now_ms)
  status=$(http POST /api/invoices "{\"patientId\":\"$patient_id\",\"invoiceNumber\":\"SMOKE-INV-$(now_ms)-$run\",\"amount\":\"10.00\",\"currency\":\"USD\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms invoice_create "$ms"
  expect_2xx "$status" "invoice create"
  body="$(cat "$BODY_FILE")"
  invoice_id="$(expect_field "$body" "d['id']" "invoice create response")"
  expect_status "$body" "DRAFT" "invoice create"
  transition_resource "$token" invoices "$invoice_id" ISSUED
  transition_resource "$token" invoices "$invoice_id" PAID
}

# ---- Journey stage: dashboard — all eleven numeric count keys; totals at ----
# ---- least consistent with the journey just executed. The Task 8 seed ----
# ---- deliberately contains open/active/draft/issued/void fixtures, so no ----
# ---- status-aware key is required to be zero. ----
journey_dashboard() { # <token>
  local token=$1 status body k value t1 ms
  t1=$(now_ms)
  status=$(http GET /api/dashboard '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms dashboard "$ms"
  expect_2xx "$status" "dashboard"
  body="$(cat "$BODY_FILE")"
  for k in patients appointments admissions emergencyVisits invoices \
           openAdmissions activeEmergencyVisits \
           invoicesDraft invoicesIssued invoicesPaid invoicesVoid; do
    value="$(jsonq "$body" "d.get('$k')")"
    if ! jsonq "$body" "isinstance(d.get('$k'),(int,float)) and not isinstance(d.get('$k'),bool)" | grep -q True; then
      fail "dashboard response missing or non-numeric '$k' key"
    fi
    if [ "$value" -lt 0 ] 2>/dev/null; then fail "dashboard '$k' is negative"; fi
  done
  for k in patients appointments admissions emergencyVisits invoices; do
    if [ "$(jsonq "$body" "d['$k']")" -lt 1 ] 2>/dev/null; then
      fail "dashboard '$k' below the journey minimum (a just-created record must be counted)"
    fi
  done
}

run_journey() { # <run> <timestamp> — one full care-operations journey
  local run=$1 ts=$2 token patient_id
  token="$(journey_login "$SMOKE_USER")"
  patient_id="$(journey_patient "$token" "$run" "$ts")"
  journey_appointment "$token" "$patient_id" "$PROFESSIONAL_ID"
  journey_admission "$token" "$patient_id"
  journey_emergency "$token" "$patient_id"
  journey_invoice "$token" "$patient_id" "$run"
  journey_dashboard "$token"
}

TS="$(date +%s)"
total_start=$(now_ms)
for r in $(seq 1 "$RUNS"); do
  echo "[smoke] run $r/$RUNS"
  run_journey "$r" "$TS"
done
total_ms=$(( $(now_ms) - total_start ))

# ---- Summary: per-step min / median / max across runs ----
echo "-----------------[ smoke timings (ms) ]-----------------"
printf '%-30s %6s %8s %8s %8s\n' step min median max
for step in login patient_create patient_search patient_detail \
            appointment_create appointment_list \
            admission_create admissions_DISCHARGED \
            emergency_create emergency-visits_IN_TREATMENT emergency-visits_CLOSED \
            invoice_create invoices_ISSUED invoices_PAID dashboard; do
  read -r mn md mx < <(awk -v s="$step" '$1==s{print $2}' "$TIMINGS" | sort -n | awk '
    {v[NR]=$1} END {
      if (NR==0) {print "- - -"; exit}
      med = (NR%2) ? v[(NR+1)/2] : int((v[NR/2]+v[NR/2+1])/2)
      print v[1], med, v[NR]
    }')
  printf '%-30s %6s %8s %8s %8s\n' "$step" "$mn" "$md" "$mx"
done
echo "---------------------------------------------------------"
echo "SMOKE RESULT: PASS (runs=$RUNS, total_ms=$total_ms, base_url=$BASE_URL, user=$SMOKE_USER)"
