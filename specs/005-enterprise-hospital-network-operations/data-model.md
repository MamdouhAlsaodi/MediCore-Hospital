# Phase 5 Data Model

## Scope and rules

The model extends the accepted Phase 4 schema inside one synthetic network. It does not create SaaS tenants. All UUIDs are server-generated. Foreign identifiers supplied by clients are request references only and never authorization evidence.

## HospitalOrganization (existing; logical network)

- `id UUID` — primary key.
- `code VARCHAR` — existing globally unique synthetic network code.
- `name VARCHAR` — display name.

**Invariant:** Phase 5 demo has exactly one provisioned network, but code must not depend on `findAll().first()`.

## HospitalFacility (new, table `hospitals`)

- `id UUID` — primary key.
- `organization_id UUID NOT NULL` — FK to network.
- `code VARCHAR(32) NOT NULL` — unique inside organization.
- `name VARCHAR(160) NOT NULL`.
- `region_label VARCHAR(160) NOT NULL` — synthetic display label, not a private address.
- `time_zone VARCHAR(60) NOT NULL` — validated IANA zone.
- `active BOOLEAN NOT NULL DEFAULT TRUE`.
- `version BIGINT NOT NULL` — optimistic locking.

**Constraints:** unique `(organization_id, code)`; active descendants require active ancestors at service boundary.

## Branch (existing, extended)

- Add `hospital_id UUID NOT NULL` after deterministic backfill.
- Preserve `organization_id` only during compatibility/migration if needed; enforce `branch.hospital.organization == branch.organization` until the redundant column is retired in a separately verified migration.
- Existing `code`, `name`, `location_label`, `time_zone`, `active` remain.

**Constraint decision:** branch code becomes unique within hospital, not merely organization: unique `(hospital_id, code)`. Migration must prove no collisions before changing the constraint.

## ActingAssignment (existing, extended)

- Existing: account, organization, role, scope, optional branch/department, enabled.
- Add `hospital_id UUID NULL`.
- Add enum value `HOSPITAL`; retain `ORGANIZATION` as network scope.

**Valid shapes:**
- `ORGANIZATION`: hospital/branch/department columns null on assignment; selected hospital and branch are resolved during context selection.
- `HOSPITAL`: hospital non-null; assignment branch/department null; selected branch must belong to that hospital.
- `BRANCH`: hospital and branch non-null; branch belongs to hospital.
- `DEPARTMENT`: hospital non-null; department non-null; hospital and branch derive consistently from department.

**Uniqueness:** Replace null-sensitive logical uniqueness with a PostgreSQL-safe normalized/index strategy that prevents duplicates for every scope.

## ActingContext (value/contract, not table)

- `username`
- `assignmentId`
- `role`
- `scope`
- `organizationId`
- `hospitalId` *(new, always non-null for an operational request)*
- `branchId` *(always non-null)*
- `departmentId` *(nullable)*

Every field is re-derived and structurally compared on each request.

## Patient (existing, migrated to network identity)

- Add `organization_id UUID NOT NULL`.
- Retain global/network `medical_record_number` uniqueness.
- Retire `branch_id` as authority after access backfill; it may remain temporarily as a migration source only.
- Existing demographic fields remain synthetic.

**Invariant:** one patient row per network MRN; patient existence alone does not grant hospital visibility.

## PatientHospitalAccess (new, table `patient_hospital_access`)

- `id UUID`.
- `patient_id UUID NOT NULL`.
- `hospital_id UUID NOT NULL`.
- `status VARCHAR(16) NOT NULL`: `ACTIVE | REVOKED`.
- `source VARCHAR(24) NOT NULL`: `LEGACY_MIGRATION | LOCAL_REGISTRATION | TRANSFER_ACCEPTED`.
- `source_transfer_id UUID NULL`.
- `created_at TIMESTAMPTZ NOT NULL`.
- `revoked_at TIMESTAMPTZ NULL`.
- `version BIGINT NOT NULL`.

**Constraints:** unique active logical grant per `(patient_id, hospital_id)`; patient and hospital must belong to same organization.

## TransferRequest (new, table `transfer_requests`)

- `id UUID`.
- `transfer_number VARCHAR(40) NOT NULL` — unique inside organization; synthetic display ID.
- `organization_id UUID NOT NULL`.
- `patient_id UUID NOT NULL`.
- `source_hospital_id UUID NOT NULL`.
- `source_branch_id UUID NOT NULL`.
- `source_admission_id UUID NULL`.
- `destination_hospital_id UUID NOT NULL`.
- `destination_branch_id UUID NULL` until accepted.
- `destination_bed_id UUID NULL` until accepted.
- `status VARCHAR(20) NOT NULL`.
- `reason_code VARCHAR(40) NOT NULL` — bounded operational synthetic code, not clinical narrative.
- `requested_by_assignment_id UUID NOT NULL`.
- `requested_at TIMESTAMPTZ NOT NULL`.
- `accepted_at`, `transit_started_at`, `completed_at`, `cancelled_at`, `rejected_at` — nullable state timestamps.
- `version BIGINT NOT NULL`.

**Constraints:** source and destination hospitals differ; both belong to organization; branches belong to corresponding hospitals; destination bed belongs to destination branch; state timestamps match state; no mutable patient/source ownership after creation.

## TransferBedReservation (new, table `transfer_bed_reservations`)

- `id UUID`.
- `transfer_id UUID NOT NULL`.
- `bed_id UUID NOT NULL`.
- `status VARCHAR(16) NOT NULL`: `ACTIVE | CONSUMED | RELEASED`.
- `reserved_at TIMESTAMPTZ NOT NULL`.
- `released_at TIMESTAMPTZ NULL`.
- `version BIGINT NOT NULL`.

**Constraints:** one reservation per transfer; partial unique index allowing only one `ACTIVE` reservation per bed; reservation bed equals transfer destination bed.

## IdempotencyRecord (new, table `idempotency_records`)

- `id UUID`.
- `assignment_id UUID NOT NULL`.
- `operation VARCHAR(64) NOT NULL`.
- `idempotency_key VARCHAR(128) NOT NULL`.
- `request_fingerprint CHAR(64) NOT NULL`.
- `resource_type VARCHAR(64) NOT NULL`.
- `resource_id UUID NULL`.
- `http_status INTEGER NULL`.
- `state VARCHAR(16) NOT NULL`: `IN_PROGRESS | COMPLETED`.
- `created_at TIMESTAMPTZ NOT NULL`.
- `completed_at TIMESTAMPTZ NULL`.

**Constraints:** unique `(assignment_id, operation, idempotency_key)`; key is bounded and never emitted as telemetry label.

## AuditEvent (existing, extended)

- Add `hospital_id UUID NULL` for acting hospital.
- Add bounded transfer context where applicable: `source_hospital_id`, `destination_hospital_id`, `transfer_id` nullable.
- Preserve actor, action, resource, occurredAt, correlationId, assignment, role, scope, organization, branch, department.

Legacy/bootstrap null context remains visible only under existing explicit legacy rules; it is never guessed.

## Lifecycle invariants

### Hospital/branch

- Inactive network/hospital/branch cannot be selected for a new context.
- Disabling an ancestor invalidates outstanding tokens on next request reload.

### Patient access

- A hospital without active access cannot list, search, fetch, mutate, or infer the patient.
- Transfer acceptance may create the destination access grant; rollback removes no pre-existing grant.

### Transfer

```text
REQUESTED -> ACCEPTED -> IN_TRANSIT -> COMPLETED
REQUESTED -> REJECTED
REQUESTED -> CANCELLED
ACCEPTED  -> CANCELLED
```

- `REJECTED`, `CANCELLED`, and `COMPLETED` are terminal.
- Only `ACCEPTED` owns an active reservation; `IN_TRANSIT` retains it until completion; cancellation releases it.
- Completion consumes the reservation and creates/binds exactly one destination admission.
- Every successful transition increments optimistic version and writes exactly one success audit event in the same transaction.

## Migration proof fixtures

1. Empty schema applies all migrations once and restarts with zero pending.
2. Phase 4-shaped synthetic baseline maps all branches to `LEGACY-HOSPITAL-001`, derives patient organization, and creates access grants.
3. Duplicate proposed hospital/branch codes fail before constraint replacement.
4. Orphan/mismatched branch, patient, assignment, or audit ownership blocks migration rather than being guessed.
5. Backfill is deterministic and restart-idempotent.
