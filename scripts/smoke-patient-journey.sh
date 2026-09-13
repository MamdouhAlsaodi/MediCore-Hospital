#!/usr/bin/env bash
# smoke-patient-journey.sh — repeatable local performance smoke for the
# complete branch-aware care-operations journey (docs/plan3.md Task 14,
# extending the docs/plan2.md Task 9 care-operations journey).
#
# Scope: Training/Portfolio evidence only. This script makes NO production
# capacity claim; it measures the demonstrated workflow against a local,
# synthetic-data backend so regressions become visible.
#
# The script boots nothing itself and cleans no processes by design: it may
# only target a disposable ephemeral/local demo backend (e.g. in-memory H2),
# never shared or live data. The operator starts that disposable backend
# first with random process-local credentials and stops it afterwards
# (docs/runbook.md):
#
#   cd backend
#   MEDICORE_DEMO_SEED=true \
#   SERVER_PORT=<loopback port> \
#   SPRING_DATASOURCE_URL='jdbc:h2:mem:smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1' \
#   SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop \
#   HOSPITAL_ADMIN_PASSWORD="<disposable random local value, >=12 chars>" \
#   HOSPITAL_JWT_SECRET="<disposable random local value>" \
#   mvn spring-boot:run
#
# Environment (secrets only from the environment; nothing is hardcoded or
# echoed; error messages never include response bodies — they can carry
# tokens):
#   BASE_URL                 backend base URL (default loopback http://127.0.0.1:5501)
#   RUNS                     repetitions of the journey (default 3, minimum 1)
#   HOSPITAL_SMOKE_USERNAME  smoke login username (default admin)
#   HOSPITAL_SMOKE_PASSWORD  REQUIRED; the login password. For the local smoke
#                            backend this is the same disposable random value
#                            supplied as HOSPITAL_ADMIN_PASSWORD at startup.
#                            Never a real or shared secret.
#
# The account must resolve to an enabled ADMIN assignment with ORGANIZATION
# scope (the demo seed plus the bootstrap provisioning provide exactly this
# for the local admin account), because the journey includes the
# organization-scoped network dashboard. The organization must also have at
# least two active branches: the journey proves branch isolation by switching
# between them.
#
# Journey per run (each HTTP call timed, ms): login -> select the allowed
# acting context (POST /api/auth/context onto the org-ADMIN assignment and
# the first active branch) -> create synthetic patient -> patient search ->
# patient detail -> read the professional's staff availability -> create an
# appointment inside one returned availability interval at that list's earliest
# conflict-free slot (never overlapping an appointment already on the books)
# -> POST an overlapping
# appointment and require the shared 409 -> create two beds -> admit the
# patient with bed A (server sets ADMITTED and occupies A) -> atomically
# transfer to bed B (A must be AVAILABLE again and B OCCUPIED; any partial
# bed mutation fails the run) -> discharge (the sole ADMITTED -> DISCHARGED
# transition with a server-stamped dischargedAt; B must be released back to
# AVAILABLE) -> emergency visit through its only legal
# WAITING -> IN_TREATMENT -> CLOSED path -> simulated invoice (FINANCIAL
# SIMULATION ONLY: display-only demo amount and currency label; no payments,
# gateways, taxes, or real money) through DRAFT -> ISSUED -> PAID -> branch
# dashboard (typed summary must match the acting branch) -> organization
# network dashboard (every total must equal the exact sum of the returned
# per-branch summaries) -> filtered audit evidence (the created patient's
# event must be found under the branch/resourceType filters and carry the
# full acting context: assignment, role, scope, organization, branch) ->
# switch to the second active branch and prove every row created above is
# absent there (search miss, 404 detail reads, list matches of zero, no
# audit row) while the branch dashboard now reports the other branch.
#
# Branch-bound claims are verified through observable API behavior only —
# scoped reads, 404s, dashboards, and audit rows. The script never decodes
# or trusts token claims.
#
# Failure contract: exits non-zero on network failure, any response outside
# the asserted contract — wrong status (including a missing 409 on the
# overlapping appointment or a missing 404 on a cross-branch read), a
# missing/malformed JSON field (session token/roles/assignments/acting
# context, created record ids, server-returned lifecycle statuses, list
# arrays, the typed dashboard count keys, the audit context fields), scope
# leakage (a primary-branch row visible from the other branch), a partial
# bed mutation (wrong occupancy on either bed after transfer or discharge),
# an absent audit context, or a dashboard identity/total mismatch. Error
# messages name the failed contract only — never response bodies.
#
# Data: every created record is synthetic and unique per run (SMOKE- MRNs,
# synthetic names, synthetic.example.test emails, SMOKE-INV- invoice
# numbers); no clinical or payment semantics. Nothing is cleaned up: run
# only against a disposable local demo database (e.g. in-memory H2), never
# shared data.
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:5501}"
RUNS="${RUNS:-3}"
SMOKE_USER="${HOSPITAL_SMOKE_USERNAME:-admin}"
AVAIL_FROM="2030-01-01T00:00:00"   # window covering the seeded availability fixtures
AVAIL_TO="2033-01-01T00:00:00"
APPT_MINUTES=30

fail() { printf '[smoke] FAIL: %s\n' "$1" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "required tool '$1' not found in PATH"; }
need curl; need python3; need awk; need date

if [ -z "${HOSPITAL_SMOKE_PASSWORD:-}" ]; then
  fail "HOSPITAL_SMOKE_PASSWORD is required (the disposable login password of the local smoke backend; see header comment)"
fi
case "$RUNS" in ''|*[!0-9]*) fail "RUNS must be a positive integer";; esac
[ "$RUNS" -ge 1 ] || fail "RUNS must be a positive integer"

now_ms() { date +%s%3N; }

BODY_FILE="$(mktemp)"
AVAIL_FILE="$(mktemp)"
APPTS_FILE="$(mktemp)"
TIMINGS="$(mktemp)"
trap 'rm -f "$BODY_FILE" "$AVAIL_FILE" "$APPTS_FILE" "$TIMINGS"' EXIT

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

# json_count BODY EXPR -> integer count; fails closed on unparseable output
json_count() {
  local value
  value="$(jsonq "$1" "$2")"
  case "$value" in ''|*[!0-9]*) fail "a JSON count assertion could not be evaluated";; esac
  printf '%s' "$value"
}

expect_2xx() { # expect_2xx <status> <what>
  case "$1" in 2??) ;; *) fail "$2 returned HTTP $1 (expected 2xx)";; esac
}

expect_code() { # expect_code <status> <expected> <what>
  if [ ! "$1" = "$2" ]; then fail "$3 returned HTTP $1 (expected $2)"; fi
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

# expect_branch BODY EXPECTED WHAT — assert the response's server-stamped
# branch ownership equals the expected acting branch.
expect_branch() { # <json-body> <expected-branch-id> <what>
  local got
  got="$(jsonq "$1" "d.get('branchId')")"
  if [ ! "$got" = "$2" ]; then
    fail "$3 is not owned by the expected branch (scope or context mismatch)"
  fi
}

# expect_error_status EXPECTED WHAT — assert the shared ApiError body of the
# last response carries the expected numeric status. The body is never printed.
expect_error_status() { # <expected> <what>
  local got
  got="$(jsonq "$(cat "$BODY_FILE")" "d.get('status')")"
  if [ ! "$got" = "$1" ]; then
    fail "$2 error body status is '$got' (expected $1)"
  fi
}

# transition_resource TOKEN ENDPOINT ID TARGET [EXPECTED_BRANCH] — timed
# server-owned status transition (PUT /api/<endpoint>/<id>/status); asserts
# 2xx, the returned status, and (when given) the branch ownership. Leaves the
# response body in BODY_FILE for follow-up field assertions.
transition_resource() { # <token> <endpoint> <id> <target-status> [expected-branch-id]
  local token=$1 endpoint=$2 id=$3 target=$4 branch=${5:-} status body t1 ms
  t1=$(now_ms)
  status=$(http PUT "/api/$endpoint/$id/status" "{\"status\":\"$target\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms "${endpoint}_${target}" "$ms"
  expect_2xx "$status" "$endpoint transition -> $target"
  body="$(cat "$BODY_FILE")"
  expect_status "$body" "$target" "$endpoint transition -> $target"
  if [ -n "$branch" ]; then expect_branch "$body" "$branch" "$endpoint transition -> $target"; fi
}

# dt_eq A B -> True/False; compares ISO timestamps as datetimes (the server
# omits zero seconds in echoed LocalDateTime strings).
dt_eq() {
  python3 -c 'from datetime import datetime; import sys
try:
    print(datetime.fromisoformat(sys.argv[1]) == datetime.fromisoformat(sys.argv[2]))
except Exception:
    print("False")' "$1" "$2"
}

# slot_for_run — reads the availability JSON in AVAIL_FILE and the acting
# branch's appointment list in APPTS_FILE and prints the earliest
# deterministic APPT_MINUTES slot inside a returned interval that overlaps no
# appointment already on the books for this professional (seeded fixtures and
# earlier runs' bookings included; the dedicated overlap-409 probe supplies
# its own collision). 15-minute search stride, intervals in start order.
# Never mutates anything.
slot_for_run() {
  python3 - "$AVAIL_FILE" "$APPTS_FILE" "$APPT_MINUTES" "$PROFESSIONAL_ID" <<'PY'
import json, sys
from datetime import datetime, timedelta
avail_path, appt_path, minutes, prof = sys.argv[1:5]
minutes = int(minutes)
try:
    rows = json.load(open(avail_path))
    appts = json.load(open(appt_path))
except Exception:
    sys.exit(3)
if not isinstance(rows, list) or not rows:
    sys.exit(3)
if not isinstance(appts, list):
    appts = []
busy = []
for a in appts:
    if not isinstance(a, dict) or a.get("professionalId") != prof:
        continue
    st, en = a.get("scheduledAt"), a.get("endsAt")
    if not st or not en:
        continue
    try:
        busy.append((datetime.fromisoformat(st), datetime.fromisoformat(en)))
    except Exception:
        pass
def free(cs, ce):
    return not any(cs < be and bs < ce for bs, be in busy)
for iv in sorted((r for r in rows if r.get("startsAt") and r.get("endsAt")),
                 key=lambda r: r["startsAt"]):
    try:
        s = datetime.fromisoformat(iv["startsAt"])
        e = datetime.fromisoformat(iv["endsAt"])
    except Exception:
        continue
    span = int((e - s).total_seconds() // 60)
    off = 0
    while off + minutes <= span:
        cs = s + timedelta(minutes=off)
        if free(cs, cs + timedelta(minutes=minutes)):
            print(cs.strftime("%Y-%m-%dT%H:%M:%S"))
            sys.exit(0)
        off += 15
sys.exit(4)
PY
}

# plus_minutes ISO MINUTES -> ISO; server-side window-end arithmetic mirror
plus_minutes() {
  python3 -c 'from datetime import datetime,timedelta; import sys; print((datetime.fromisoformat(sys.argv[1])+timedelta(minutes=int(sys.argv[2]))).isoformat())' "$1" "$2"
}

# assert_branch_dashboard BODY BRANCH_ID BRANCH_CODE WHAT — typed branch
# summary contract: acting-branch identity, all sixteen numeric count keys
# non-negative, and the five whole-table totals at least counting the rows
# the journey just created. The seed deliberately keeps occupied/open/draft
# fixtures, so zero is never required anywhere.
assert_branch_dashboard() { # <body> <branch-id> <branch-code> <what>
  local body=$1 branch_id=$2 branch_code=$3 what=$4 k
  if [ ! "$(jsonq "$body" "d.get('branchId')")" = "$branch_id" ]; then
    fail "$what reports the wrong branch"
  fi
  if [ ! "$(jsonq "$body" "d.get('branchCode')")" = "$branch_code" ]; then
    fail "$what reports the wrong branch code"
  fi
  for k in patients appointments admissions emergencyVisits invoices \
           openAdmissions activeEmergencyVisits bedsAvailable bedsOccupied \
           bedsMaintenance bedsOutOfService todayAppointments \
           invoicesDraft invoicesIssued invoicesPaid invoicesVoid; do
    if ! jsonq "$body" "isinstance(d.get('$k'),(int,float)) and not isinstance(d.get('$k'),bool)" | grep -q True; then
      fail "$what missing or non-numeric '$k' key"
    fi
    [ "$(jsonq "$body" "d['$k']")" -ge 0 ] 2>/dev/null || fail "$what '$k' is negative"
  done
  for k in patients appointments admissions emergencyVisits invoices; do
    [ "$(jsonq "$body" "d['$k']")" -ge 1 ] 2>/dev/null || fail "$what '$k' below the journey minimum (a just-created record must be counted)"
  done
}

# assert_network_dashboard BODY ORG_ID BRANCH_ID BRANCH_CODE WHAT — typed
# network summary contract (ORGANIZATION-ADMIN only): the organization
# identity, at least two per-branch summaries, every numeric total exactly
# equal to the sum over the returned branches (never client aggregation),
# and the acting branch present with its code and counted patient.
assert_network_dashboard() { # <body-file> <org-id> <branch-id> <branch-code> <what>
  local verdict
  verdict="$(python3 - "$2" "$3" "$4" "$1" <<'PY'
import json, sys
org_id, branch_id, branch_code, path = sys.argv[1:5]
keys = ["patients", "appointments", "admissions", "emergencyVisits", "invoices",
        "openAdmissions", "activeEmergencyVisits", "bedsAvailable", "bedsOccupied",
        "bedsMaintenance", "bedsOutOfService", "todayAppointments",
        "invoicesDraft", "invoicesIssued", "invoicesPaid", "invoicesVoid"]
try:
    d = json.load(open(path))
except Exception:
    print("network dashboard response is not valid JSON"); sys.exit(0)
if d.get("organizationId") != org_id:
    print("network dashboard reports the wrong organization"); sys.exit(0)
branches = d.get("branches")
if not isinstance(branches, list) or len(branches) < 2:
    print("network dashboard must list at least two branch summaries"); sys.exit(0)
for k in keys:
    total = d.get(k)
    if not isinstance(total, (int, float)) or isinstance(total, bool):
        print(f"network dashboard missing or non-numeric '{k}'"); sys.exit(0)
    if total < 0:
        print(f"network dashboard '{k}' is negative"); sys.exit(0)
    s = 0
    for b in branches:
        v = b.get(k)
        if not isinstance(v, (int, float)) or isinstance(v, bool):
            print(f"network branch summary missing or non-numeric '{k}'"); sys.exit(0)
        s += v
    if total != s:
        print(f"network total '{k}' does not equal the sum of its branch summaries"); sys.exit(0)
match = [b for b in branches if b.get("branchId") == branch_id]
if not match:
    print("network dashboard does not list the acting branch summary"); sys.exit(0)
if match[0].get("branchCode") != branch_code:
    print("network acting-branch summary reports the wrong branch code"); sys.exit(0)
if not isinstance(match[0].get("patients"), (int, float)) or match[0]["patients"] < 1:
    print("network acting-branch summary does not count the patient created this run"); sys.exit(0)
print("ok")
PY
)"
  if [ ! "$verdict" = "ok" ]; then fail "$5: $verdict"; fi
}

echo "[smoke] backend: $BASE_URL  runs: $RUNS  user: $SMOKE_USER"

# ---- Preflight: backend must already be up (permitAll health endpoint) ----
status=$(http GET /actuator/health)
if [ ! "$status" = 200 ]; then
  fail "preflight /actuator/health returned HTTP $status — backend not reachable at $BASE_URL; start it first (docs/runbook.md)"
fi

# ---- Preflight login: credentials and the org-ADMIN assignment must resolve ----
status=$(http POST /api/auth/login "{\"username\":\"$SMOKE_USER\",\"password\":\"$HOSPITAL_SMOKE_PASSWORD\"}")
if [ ! "$status" = 200 ]; then
  fail "preflight login returned HTTP $status — check HOSPITAL_SMOKE_USERNAME/HOSPITAL_SMOKE_PASSWORD against the locally started backend"
fi
body="$(cat "$BODY_FILE")"
if ! jsonq "$body" "len([r for r in d['roles'] if r == 'ADMIN'])" | grep -qE '^[1-9]'; then
  fail "smoke user '$SMOKE_USER' lacks the ADMIN role — the branch-aware journey (network dashboard, audit) cannot run"
fi
PREFLIGHT_TOKEN="$(expect_field "$body" "d['accessToken']" "preflight login response")"
ASSIGNMENT_ID="$(expect_field "$body" "next((a['id'] for a in d['assignments'] if a['role']=='ADMIN' and a['scope']=='ORGANIZATION' and a['enabled']), None)" "ORGANIZATION-scoped ADMIN assignment")"
ORG_ID="$(expect_field "$body" "d['actingContext']['organizationId']" "preflight acting context organization")"
LOGIN_BRANCH_ID="$(expect_field "$body" "d['actingContext']['branchId']" "preflight acting context branch")"

# ---- Reference hierarchy: at least two active branches; the journey proves ----
# ---- isolation by switching between the first two (deterministic code order).
status=$(http GET /api/branches '' "$PREFLIGHT_TOKEN")
expect_2xx "$status" "GET /api/branches"
body="$(cat "$BODY_FILE")"
if [ "$(json_count "$body" "len([b for b in d if b.get('active')])")" -lt 2 ]; then
  fail "fewer than two active branches — start the backend with MEDICORE_DEMO_SEED=true (docs/runbook.md) so the three-branch demo cohort exists; the isolation proof cannot run"
fi
PRIMARY_ID="$(expect_field "$body" "next(b['id'] for b in d if b.get('active'))" "primary active branch")"
PRIMARY_CODE="$(expect_field "$body" "next(b['code'] for b in d if b.get('active'))" "primary active branch code")"
OTHER_ID="$(expect_field "$body" "[b for b in d if b.get('active')][1]['id']" "second active branch")"
OTHER_CODE="$(expect_field "$body" "[b for b in d if b.get('active')][1]['code']" "second active branch code")"
if [ ! "$LOGIN_BRANCH_ID" = "$PRIMARY_ID" ]; then
  fail "login did not select the first active branch — the deterministic primary context is not what the journey assumes"
fi

# ---- Reference resolution (once): a professional on the primary branch ----
status=$(http GET /api/staff '' "$PREFLIGHT_TOKEN")
expect_2xx "$status" "GET /api/staff"
body="$(cat "$BODY_FILE")"
if ! [ "$(json_count "$body" "len(d) if isinstance(d,list) else -1")" -gt 0 ] 2>/dev/null; then
  fail "staff list has no resolvable professional reference on the primary branch. Start the backend with MEDICORE_DEMO_SEED=true (docs/runbook.md) so DEMO-STAFF professionals exist."
fi
PROFESSIONAL_ID="$(expect_field "$body" "d[0]['id']" "staff list entry")"
echo "[smoke] primary branch: $PRIMARY_CODE  other branch: $OTHER_CODE  professional: $PROFESSIONAL_ID"

# ---- Journey stage: login (timed) — prints the bearer token ----
journey_login() { # <username>
  local user=$1 status body t1 ms token
  t1=$(now_ms)
  status=$(http POST /api/auth/login "{\"username\":\"$user\",\"password\":\"$HOSPITAL_SMOKE_PASSWORD\"}")
  ms=$(( $(now_ms) - t1 )); step_ms login "$ms"
  expect_2xx "$status" "login"
  body="$(cat "$BODY_FILE")"
  token="$(expect_field "$body" "d['accessToken']" "login response")"
  if ! jsonq "$body" "len([r for r in d['roles'] if r == 'ADMIN'])" | grep -qE '^[1-9]'; then
    fail "smoke user '$user' lacks the ADMIN role — the branch-aware journey cannot run"
  fi
  printf '%s' "$token"
}

# ---- Journey stage: select the allowed acting context on the org-ADMIN ----
# ---- assignment and the primary branch; prints the replacement token ----
journey_context_select() { # <token> <branch-id>
  local token=$1 branch_id=$2 status body t1 ms new_token
  t1=$(now_ms)
  status=$(http POST /api/auth/context "{\"assignmentId\":\"$ASSIGNMENT_ID\",\"branchId\":\"$branch_id\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms context_select "$ms"
  expect_2xx "$status" "context select"
  body="$(cat "$BODY_FILE")"
  new_token="$(expect_field "$body" "d['accessToken']" "context select response")"
  if [ ! "$(jsonq "$body" "d['actingContext']['branchId']")" = "$branch_id" ]; then
    fail "context select did not activate the requested allowed branch"
  fi
  if [ ! "$(jsonq "$body" "d['actingContext']['assignmentId']")" = "$ASSIGNMENT_ID" ]; then
    fail "context select activated a foreign assignment"
  fi
  printf '%s' "$new_token"
}

# ---- Journey stage: create the run's distinct synthetic patient, then ----
# ---- prove search and detail resolve it inside the acting branch ----
journey_patient() { # <token> <run> <timestamp> -> prints the created patient id
  local token=$1 run=$2 ts=$3 mrn patient_id status body t1 ms
  mrn="SMOKE-${ts}-${run}"
  t1=$(now_ms)
  status=$(http POST /api/patients "{\"medicalRecordNumber\":\"$mrn\",\"fullName\":\"Smoke Branch Patient Run $run\",\"dateOfBirth\":\"2000-01-01\",\"sex\":\"unspecified\",\"phone\":\"+10000000000\",\"email\":\"smoke-branch-run${run}-${ts}@synthetic.example.test\",\"nationalId\":\"NID-SMOKE-${ts}-${run}\",\"address\":\"1 Synthetic Street\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_create "$ms"
  expect_2xx "$status" "patient create ($mrn)"
  body="$(cat "$BODY_FILE")"
  patient_id="$(expect_field "$body" "d['id']" "patient create response")"
  expect_branch "$body" "$PRIMARY_ID" "patient create"
  t1=$(now_ms)
  status=$(http GET "/api/patients?q=Smoke%20Branch%20Patient%20Run%20$run" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms patient_search "$ms"
  expect_2xx "$status" "patient search"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(json_count "$body" "len([p for p in d if p.get('medicalRecordNumber')=='$mrn'])")" -ge 1 ]; then
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
  expect_branch "$body" "$PRIMARY_ID" "patient detail"
  printf '%s' "$patient_id"
}

# ---- Journey stage: read the professional's availability (the scheduling ----
# ---- ground truth) and pick this run's slot inside a returned interval ----
journey_availability() { # <token> <run> -> prints the scheduledAt slot
  local token=$1 run=$2 status body t1 ms slot
  t1=$(now_ms)
  status=$(http GET "/api/staff/$PROFESSIONAL_ID/availability?from=$AVAIL_FROM&to=$AVAIL_TO" '' "$token")
  expect_2xx "$status" "staff availability read"
  cp "$BODY_FILE" "$AVAIL_FILE"
  status=$(http GET /api/appointments '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms staff_availability "$ms"
  expect_2xx "$status" "branch appointment list read"
  cp "$BODY_FILE" "$APPTS_FILE"
  body="$(cat "$AVAIL_FILE")"
  if ! [ "$(json_count "$body" "len(d) if isinstance(d,list) else -1")" -ge 1 ] 2>/dev/null; then
    fail "staff availability returned no interval — the seeded dated fixtures are missing (start with MEDICORE_DEMO_SEED=true)"
  fi
  if ! [ "$(jsonq "$body" "d[0].get('branchId')=='$PRIMARY_ID' and d[0].get('staffMemberId')=='$PROFESSIONAL_ID'")" = "True" ]; then
    fail "staff availability rows do not belong to the acting branch/professional"
  fi
  slot="$(slot_for_run)" || fail "staff availability contains no conflict-free interval long enough for a ${APPT_MINUTES}-minute appointment"
  printf '%s' "$slot"
}

# ---- Journey stage: appointment inside the returned interval, then the ----
# ---- overlapping second booking must be refused with the shared 409 ----
journey_appointment() { # <token> <patient_id> <slot> -> prints the appointment id
  local token=$1 patient_id=$2 slot=$3 appt_id status body t1 ms overlap end
  end="$(plus_minutes "$slot" "$APPT_MINUTES")"
  overlap="$(plus_minutes "$slot" 15)"
  t1=$(now_ms)
  status=$(http POST /api/appointments "{\"patientId\":\"$patient_id\",\"professionalId\":\"$PROFESSIONAL_ID\",\"scheduledAt\":\"$slot\",\"durationMinutes\":$APPT_MINUTES,\"type\":\"consultation\",\"status\":\"scheduled\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms appointment_create "$ms"
  expect_2xx "$status" "appointment create"
  body="$(cat "$BODY_FILE")"
  appt_id="$(expect_field "$body" "d['id']" "appointment create response")"
  expect_branch "$body" "$PRIMARY_ID" "appointment create"
  if [ "$(jsonq "$body" "d.get('patientId')")" != "$patient_id" ] || [ "$(jsonq "$body" "d.get('professionalId')")" != "$PROFESSIONAL_ID" ]; then
    fail "appointment create does not echo the verified references"
  fi
  expect_status "$body" "scheduled" "appointment create"
  if [ ! "$(dt_eq "$(jsonq "$body" "d.get('scheduledAt')")" "$slot")" = "True" ]; then
    fail "appointment create echoed a different scheduledAt"
  fi
  if [ ! "$(dt_eq "$(jsonq "$body" "d.get('endsAt')")" "$end")" = "True" ]; then
    fail "appointment endsAt is not the server-computed window end"
  fi
  t1=$(now_ms)
  status=$(http POST /api/appointments "{\"patientId\":\"$patient_id\",\"professionalId\":\"$PROFESSIONAL_ID\",\"scheduledAt\":\"$overlap\",\"durationMinutes\":$APPT_MINUTES,\"type\":\"consultation\",\"status\":\"scheduled\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms appointment_conflict_409 "$ms"
  expect_code "$status" 409 "overlapping appointment"
  expect_error_status 409 "overlapping appointment"
  printf '%s' "$appt_id"
}

# ---- Journey stage: two synthetic beds, then admit with bed A; the server ----
# ---- sets ADMITTED and occupies A through a live assignment ----
journey_admission() { # <token> <patient_id> <run> <timestamp> -> prints "admission_id bed_a_id bed_b_id"
  local token=$1 patient_id=$2 run=$3 ts=$4 admission_id status body t1 ms bed_a bed_b
  bed_a="SMOKE-${ts}-${run}-A"
  bed_b="SMOKE-${ts}-${run}-B"
  t1=$(now_ms)
  status=$(http POST /api/beds "{\"ward\":\"Smoke Ward\",\"room\":\"S-101\",\"bedNumber\":\"$bed_a\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms bed_create_a "$ms"
  expect_2xx "$status" "bed A create"
  body="$(cat "$BODY_FILE")"
  BED_A_ID="$(expect_field "$body" "d['id']" "bed A create response")"
  expect_branch "$body" "$PRIMARY_ID" "bed A create"
  if [ ! "$(jsonq "$body" "d.get('occupancyStatus')")" = "AVAILABLE" ]; then
    fail "bed A was not created AVAILABLE"
  fi
  t1=$(now_ms)
  status=$(http POST /api/beds "{\"ward\":\"Smoke Ward\",\"room\":\"S-101\",\"bedNumber\":\"$bed_b\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms bed_create_b "$ms"
  expect_2xx "$status" "bed B create"
  body="$(cat "$BODY_FILE")"
  BED_B_ID="$(expect_field "$body" "d['id']" "bed B create response")"
  expect_branch "$body" "$PRIMARY_ID" "bed B create"
  if [ ! "$(jsonq "$body" "d.get('occupancyStatus')")" = "AVAILABLE" ]; then
    fail "bed B was not created AVAILABLE"
  fi
  t1=$(now_ms)
  status=$(http POST /api/admissions "{\"patientId\":\"$patient_id\",\"admittedAt\":\"$(date -u '+%Y-%m-%dT%H:%M:%S')\",\"reason\":\"Demo synthetic smoke admission with bed assignment\",\"bedId\":\"$BED_A_ID\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms admission_create "$ms"
  expect_2xx "$status" "admission create"
  body="$(cat "$BODY_FILE")"
  admission_id="$(expect_field "$body" "d['id']" "admission create response")"
  expect_status "$body" "ADMITTED" "admission create"
  expect_branch "$body" "$PRIMARY_ID" "admission create"
  if [ ! "$(jsonq "$body" "d['currentBed']['bedId']")" = "$BED_A_ID" ]; then
    fail "admission create did not take bed A"
  fi
  t1=$(now_ms)
  status=$(http GET "/api/beds/$BED_A_ID" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms bed_occupy_verify "$ms"
  expect_2xx "$status" "bed A read after admission"
  if [ ! "$(jsonq "$(cat "$BODY_FILE")" "d.get('occupancyStatus')")" = "OCCUPIED" ]; then
    fail "bed A is not OCCUPIED after the admission took it"
  fi
  printf '%s %s %s' "$admission_id" "$BED_A_ID" "$BED_B_ID"
}

# ---- Journey stage: atomic bed transfer A -> B; BOTH beds must end in the ----
# ---- correct state (any partial mutation fails the run), and the later ----
# ---- discharge must release B ----
journey_transfer_discharge() { # <token> <admission_id> <bed_a_id> <bed_b_id>
  local token=$1 admission_id=$2 bed_a=$3 bed_b=$4 status body t1 ms
  t1=$(now_ms)
  status=$(http PUT "/api/admissions/$admission_id/bed" "{\"bedId\":\"$bed_b\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms admission_bed_transfer "$ms"
  expect_2xx "$status" "admission bed transfer"
  body="$(cat "$BODY_FILE")"
  if [ ! "$(jsonq "$body" "d['currentBed']['bedId']")" = "$bed_b" ]; then
    fail "bed transfer did not move the admission to bed B"
  fi
  expect_status "$body" "ADMITTED" "admission bed transfer"
  t1=$(now_ms)
  status=$(http GET "/api/beds/$bed_a" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms bed_transfer_release_verify "$ms"
  expect_2xx "$status" "bed A read after transfer"
  if [ ! "$(jsonq "$(cat "$BODY_FILE")" "d.get('occupancyStatus')")" = "AVAILABLE" ]; then
    fail "bed A was not released by the transfer (partial bed mutation)"
  fi
  t1=$(now_ms)
  status=$(http GET "/api/beds/$bed_b" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms bed_transfer_occupy_verify "$ms"
  expect_2xx "$status" "bed B read after transfer"
  if [ ! "$(jsonq "$(cat "$BODY_FILE")" "d.get('occupancyStatus')")" = "OCCUPIED" ]; then
    fail "bed B is not OCCUPIED after the transfer (partial bed mutation)"
  fi
  t1=$(now_ms)
  status=$(http PUT "/api/admissions/$admission_id/status" '{"status":"DISCHARGED"}' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms admissions_DISCHARGED "$ms"
  expect_2xx "$status" "admission discharge"
  body="$(cat "$BODY_FILE")"
  expect_status "$body" "DISCHARGED" "admission discharge"
  expect_field "$body" "d['dischargedAt']" "discharge response dischargedAt" >/dev/null
  expect_branch "$body" "$PRIMARY_ID" "admission discharge"
  t1=$(now_ms)
  status=$(http GET "/api/beds/$bed_b" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms bed_discharge_release_verify "$ms"
  expect_2xx "$status" "bed B read after discharge"
  if [ ! "$(jsonq "$(cat "$BODY_FILE")" "d.get('occupancyStatus')")" = "AVAILABLE" ]; then
    fail "bed B was not released by the discharge (partial bed mutation)"
  fi
}

# ---- Journey stage: emergency visit through its only legal path (the ----
# ---- triage label is a neutral 1-5 demo value with no clinical meaning) ----
journey_emergency() { # <token> <patient_id>
  local token=$1 patient_id=$2 visit_id status body t1 ms
  t1=$(now_ms)
  status=$(http POST /api/emergency-visits "{\"patientId\":\"$patient_id\",\"arrivalAt\":\"$(date -u '+%Y-%m-%dT%H:%M:%S')\",\"triageLevel\":\"3\",\"chiefComplaint\":\"Demo synthetic smoke intake\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms emergency_create "$ms"
  expect_2xx "$status" "emergency visit create"
  body="$(cat "$BODY_FILE")"
  visit_id="$(expect_field "$body" "d['id']" "emergency visit create response")"
  expect_status "$body" "WAITING" "emergency visit create"
  expect_branch "$body" "$PRIMARY_ID" "emergency visit create"
  transition_resource "$token" emergency-visits "$visit_id" IN_TREATMENT "$PRIMARY_ID"
  transition_resource "$token" emergency-visits "$visit_id" CLOSED "$PRIMARY_ID"
}

# ---- Journey stage: invoice — FINANCIAL SIMULATION ONLY (display-only demo ----
# ---- amount and currency; no payments, gateways, taxes, or real money) ----
journey_invoice() { # <token> <patient_id> <run> <timestamp> -> prints the invoice id
  local token=$1 patient_id=$2 run=$3 ts=$4 invoice_id status body t1 ms number
  number="SMOKE-INV-${ts}-${run}"
  t1=$(now_ms)
  status=$(http POST /api/invoices "{\"patientId\":\"$patient_id\",\"invoiceNumber\":\"$number\",\"amount\":\"10.00\",\"currency\":\"USD\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms invoice_create "$ms"
  expect_2xx "$status" "invoice create"
  body="$(cat "$BODY_FILE")"
  invoice_id="$(expect_field "$body" "d['id']" "invoice create response")"
  expect_status "$body" "DRAFT" "invoice create"
  expect_branch "$body" "$PRIMARY_ID" "invoice create"
  if [ ! "$(jsonq "$body" "d.get('invoiceNumber')")" = "$number" ]; then
    fail "invoice create does not echo its unique number"
  fi
  transition_resource "$token" invoices "$invoice_id" ISSUED "$PRIMARY_ID"
  transition_resource "$token" invoices "$invoice_id" PAID "$PRIMARY_ID"
  printf '%s' "$invoice_id"
}

# ---- Journey stage: branch dashboard, then the organization network ----
# ---- dashboard (ORGANIZATION-ADMIN contract) ----
journey_dashboards() { # <token>
  local token=$1 status t1 ms
  t1=$(now_ms)
  status=$(http GET /api/dashboard/branch '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms branch_dashboard "$ms"
  expect_2xx "$status" "branch dashboard"
  assert_branch_dashboard "$(cat "$BODY_FILE")" "$PRIMARY_ID" "$PRIMARY_CODE" "branch dashboard"
  t1=$(now_ms)
  status=$(http GET /api/dashboard/network '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms network_dashboard "$ms"
  expect_2xx "$status" "network dashboard"
  assert_network_dashboard "$BODY_FILE" "$ORG_ID" "$PRIMARY_ID" "$PRIMARY_CODE" "network dashboard"
}

# ---- Journey stage: filtered audit evidence — the created patient's event ----
# ---- must be found under the branch/resourceType filters and carry the ----
# ---- full acting context ----
journey_audit() { # <token> <patient_id>
  local token=$1 patient_id=$2 status body t1 ms row_expr
  row_expr="[r for r in d if r.get('resourceType')=='Patient' and r.get('resourceId')=='$patient_id']"
  t1=$(now_ms)
  status=$(http GET "/api/audit?branchId=$PRIMARY_ID&resourceType=Patient" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms audit_context "$ms"
  expect_2xx "$status" "filtered audit read"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(json_count "$body" "len(d) if isinstance(d,list) else -1")" -ge 1 ] 2>/dev/null; then
    fail "filtered audit evidence returned no rows for the acting branch"
  fi
  if ! [ "$(json_count "$body" "len($row_expr)")" -ge 1 ]; then
    fail "filtered audit evidence has no event for the patient created this run (absent audit evidence)"
  fi
  if ! [ "$(json_count "$body" "len([r for r in d if r.get('branchId')=='$PRIMARY_ID'])")" -eq "$(json_count "$body" "len(d)")" ]; then
    fail "filtered audit evidence returned rows outside the acting branch"
  fi
  if [ ! "$(jsonq "$body" "${row_expr}[0].get('assignmentId')")" = "$ASSIGNMENT_ID" ] \
     || [ ! "$(jsonq "$body" "${row_expr}[0].get('role')")" = "ADMIN" ] \
     || [ ! "$(jsonq "$body" "${row_expr}[0].get('scope')")" = "ORGANIZATION" ] \
     || [ ! "$(jsonq "$body" "${row_expr}[0].get('organizationId')")" = "$ORG_ID" ] \
     || [ ! "$(jsonq "$body" "${row_expr}[0].get('branchId')")" = "$PRIMARY_ID" ] \
     || [ ! "$(jsonq "$body" "${row_expr}[0].get('actor')")" = "$SMOKE_USER" ]; then
    fail "the created patient's audit event does not carry the full acting context"
  fi
}

# ---- Journey stage: switch to the second allowed branch and prove every ----
# ---- row created above is absent there (branch isolation is observable ----
# ---- server behavior, never a decoded token claim) ----
journey_isolation() { # <token> <patient_id> <appointment_id> <admission_id> <invoice_id> <bed_a_id>
  local token=$1 patient_id=$2 appt_id=$3 admission_id=$4 invoice_id=$5 bed_a_id=$6
  local status body t1 ms
  t1=$(now_ms)
  status=$(http POST /api/auth/context "{\"assignmentId\":\"$ASSIGNMENT_ID\",\"branchId\":\"$OTHER_ID\"}" "$token")
  ms=$(( $(now_ms) - t1 )); step_ms context_switch "$ms"
  expect_2xx "$status" "context switch to the second allowed branch"
  body="$(cat "$BODY_FILE")"
  token="$(expect_field "$body" "d['accessToken']" "context switch response")"
  if [ ! "$(jsonq "$body" "d['actingContext']['branchId']")" = "$OTHER_ID" ]; then
    fail "context switch did not activate the second allowed branch"
  fi

  t1=$(now_ms)
  status=$(http GET "/api/patients?q=Smoke%20Branch%20Patient%20Run" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_patient_search "$ms"
  expect_2xx "$status" "isolation patient search"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(json_count "$body" "len([p for p in d if p.get('id')=='$patient_id'])")" -eq 0 ]; then
    fail "branch leakage: the patient created on the primary branch is visible from the other branch"
  fi
  t1=$(now_ms)
  status=$(http GET "/api/patients/$patient_id" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_patient_detail "$ms"
  expect_code "$status" 404 "cross-branch patient detail"
  t1=$(now_ms)
  status=$(http GET "/api/beds/$bed_a_id" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_bed "$ms"
  expect_code "$status" 404 "cross-branch bed detail"
  t1=$(now_ms)
  status=$(http GET "/api/admissions/$admission_id" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_admission "$ms"
  expect_code "$status" 404 "cross-branch admission detail"
  t1=$(now_ms)
  status=$(http GET /api/appointments '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_appointments "$ms"
  expect_2xx "$status" "isolation appointment list"
  if ! [ "$(json_count "$(cat "$BODY_FILE")" "len([a for a in d if a.get('id')=='$appt_id'])")" -eq 0 ]; then
    fail "branch leakage: the appointment created on the primary branch is visible from the other branch"
  fi
  t1=$(now_ms)
  status=$(http GET /api/emergency-visits '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_emergency "$ms"
  expect_2xx "$status" "isolation emergency list"
  if ! [ "$(json_count "$(cat "$BODY_FILE")" "len([v for v in d if v.get('branchId')=='$PRIMARY_ID'])")" -eq 0 ]; then
    fail "branch leakage: primary-branch emergency visits are visible from the other branch"
  fi
  t1=$(now_ms)
  status=$(http GET /api/invoices '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_invoices "$ms"
  expect_2xx "$status" "isolation invoice list"
  body="$(cat "$BODY_FILE")"
  if ! [ "$(json_count "$body" "len([i for i in d if i.get('id')=='$invoice_id'])")" -eq 0 ]; then
    fail "branch leakage: the invoice created on the primary branch is visible from the other branch"
  fi
  if ! [ "$(json_count "$body" "len([i for i in d if i.get('branchId')=='$PRIMARY_ID'])")" -eq 0 ]; then
    fail "branch leakage: primary-branch invoices are visible from the other branch"
  fi
  t1=$(now_ms)
  status=$(http GET "/api/audit?branchId=$OTHER_ID&resourceType=Patient" '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms isolation_audit "$ms"
  expect_2xx "$status" "isolation audit read"
  if ! [ "$(json_count "$(cat "$BODY_FILE")" "len([r for r in d if r.get('resourceId')=='$patient_id'])")" -eq 0 ]; then
    fail "branch leakage: the primary-branch patient's audit event appears under the other branch"
  fi
  t1=$(now_ms)
  status=$(http GET /api/dashboard/branch '' "$token")
  ms=$(( $(now_ms) - t1 )); step_ms branch_dashboard_other "$ms"
  expect_2xx "$status" "branch dashboard after switch"
  assert_branch_dashboard "$(cat "$BODY_FILE")" "$OTHER_ID" "$OTHER_CODE" "branch dashboard after switch"
}

run_journey() { # <run> <timestamp> — one full branch-aware care-operations journey
  local run=$1 ts=$2 token patient_id appt_id admission_id invoice_id bed_a_id bed_b_id slot
  token="$(journey_login "$SMOKE_USER")"
  token="$(journey_context_select "$token" "$PRIMARY_ID")"
  patient_id="$(journey_patient "$token" "$run" "$ts")"
  slot="$(journey_availability "$token" "$run")"
  appt_id="$(journey_appointment "$token" "$patient_id" "$slot")"
  read -r admission_id bed_a_id bed_b_id <<<"$(journey_admission "$token" "$patient_id" "$run" "$ts")"
  journey_transfer_discharge "$token" "$admission_id" "$bed_a_id" "$bed_b_id"
  journey_emergency "$token" "$patient_id"
  invoice_id="$(journey_invoice "$token" "$patient_id" "$run" "$ts")"
  journey_dashboards "$token"
  journey_audit "$token" "$patient_id"
  journey_isolation "$token" "$patient_id" "$appt_id" "$admission_id" "$invoice_id" "$bed_a_id"
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
printf '%-32s %6s %8s %8s %8s\n' step min median max
for step in login context_select patient_create patient_search patient_detail \
            staff_availability appointment_create appointment_conflict_409 \
            bed_create_a bed_create_b admission_create bed_occupy_verify \
            admission_bed_transfer bed_transfer_release_verify bed_transfer_occupy_verify \
            admissions_DISCHARGED bed_discharge_release_verify \
            emergency_create emergency-visits_IN_TREATMENT emergency-visits_CLOSED \
            invoice_create invoices_ISSUED invoices_PAID \
            branch_dashboard network_dashboard audit_context context_switch \
            isolation_patient_search isolation_patient_detail isolation_bed \
            isolation_admission isolation_appointments isolation_emergency \
            isolation_invoices isolation_audit branch_dashboard_other; do
  read -r mn md mx < <(awk -v s="$step" '$1==s{print $2}' "$TIMINGS" | sort -n | awk '
    {v[NR]=$1} END {
      if (NR==0) {print "- - -"; exit}
      med = (NR%2) ? v[(NR+1)/2] : int((v[NR/2]+v[NR/2+1])/2)
      print v[1], med, v[NR]
    }')
  printf '%-32s %6s %8s %8s %8s\n' "$step" "$mn" "$md" "$mx"
done
echo "---------------------------------------------------------"
echo "SMOKE RESULT: PASS (runs=$RUNS, total_ms=$total_ms, base_url=$BASE_URL, user=$SMOKE_USER)"
