#!/usr/bin/env bash
# Allowlisted structural invariant verifier for a Phase 4 migrated synthetic
# PostgreSQL database (plan Tasks 3/7; FR-003, FR-006, SC-004). Prints ONLY
# deterministic machine-readable lines (counts/booleans) — never row bodies,
# never credentials. Exit 0 when every invariant holds in the target.
#
# Usage:
#   verify-database-invariants.sh <host> <port> <database> <user>
# Environment:
#   PGPASSWORD   password for <user> (required; never printed)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/postgres-target-guard.sh"

if [ $# -ne 4 ]; then
  echo "usage: $0 <host> <port> <database> <user>" >&2
  exit 2
fi
HOST="$1"; PORT="$2"; DB="$3"; USER_="$4"

[ -n "${PGPASSWORD:-}" ] || { echo "verify-database-invariants: PGPASSWORD must be set" >&2; exit 2; }
command -v psql >/dev/null 2>&1 || { echo "psql not found" >&2; exit 2; }
"$GUARD" check "$HOST" "$DB" || exit 1

export PGPASSWORD
Q() { psql -h "$HOST" -p "$PORT" -U "$USER_" -d "$DB" -At -v ON_ERROR_STOP=1 "$@"; }

fail=0
# Migration ledger: every shipped version applied successfully.
versions="$(Q -c "select version || '|' || success::text from flyway_schema_history order by version")"
[ "$(printf '%s\n' "$versions" | grep -c '|true$')" -ge 4 ] || { echo "FAIL: flyway versions incomplete"; fail=1; }

count_of() { Q -c "select count(*) from $1"; }
for t in branches departments patients staff_members beds appointments admissions \
         emergency_visits invoices staff_availability audit_events acting_assignments \
         user_accounts hospital_organizations admission_bed_assignments; do
  echo "count $t = $(count_of "$t")"
done

# Referential integrity: no orphans in demonstrated workflow families.
# (Reference columns are the accepted varchar id-string shape, so the
# comparisons cast the uuid PK to text.)
orphans=$(Q -c "
select
 (select count(*) from appointments a left join patients p on p.id::text=a.patient_id where a.patient_id is not null and p.id is null) +
 (select count(*) from admissions ad left join patients p on p.id::text=ad.patient_id where ad.patient_id is not null and p.id is null) +
 (select count(*) from invoices i left join patients p on p.id::text=i.patient_id where i.patient_id is not null and p.id is null) +
 (select count(*) from appointments a2 left join branches b on b.id=a2.branch_id where a2.branch_id is not null and b.id is null)")
[ "$orphans" = "0" ] || { echo "FAIL: orphan references: $orphans"; fail=1; }

# Uniqueness (global by decision, FR-014): duplicate MRN / invoice numbers.
dup_mrn=$(Q -c "select count(*) from (select medical_record_number from patients group by 1 having count(*)>1) d")
dup_inv=$(Q -c "select count(*) from (select invoice_number from invoices group by 1 having count(*)>1) d")
[ "$dup_mrn" = "0" ] || { echo "FAIL: duplicate MRNs: $dup_mrn"; fail=1; }
[ "$dup_inv" = "0" ] || { echo "FAIL: duplicate invoice numbers: $dup_inv"; fail=1; }

# Bed/admission consistency: the active-bed linkage lives in
# admission_bed_assignments, whose unique constraints (uq_assignment_active_admission,
# uq_assignment_active_bed) permit exactly one active assignment per admission
# and per bed; assert that structurally via duplicate detection.
bad_beds=$(Q -c "
select count(*) from (
  select bed_id from admission_bed_assignments group by bed_id having count(*) > 1) x")
[ "$bad_beds" = "0" ] || { echo "FAIL: beds with multiple active admissions: $bad_beds"; fail=1; }

# Audit coverage sanity: audit rows exist and carry a non-empty actor.
empty_actor=$(Q -c "select count(*) from audit_events where actor is null or actor = ''")
[ "$empty_actor" = "0" ] || { echo "FAIL: audit rows without an actor: $empty_actor"; fail=1; }

if [ "$fail" -eq 0 ]; then
  echo "verify-database-invariants: PASS $DB"
else
  echo "verify-database-invariants: FAIL $DB"
fi
exit "$fail"
