# Phase 4 Schema Baseline (Phase 5 T006 inventory)

**Status:** Source-grounded inventory only — read from this worktree at baseline
`0c5a303a72b31e15cdac0d41773580a3585605e1`. No schema change is proposed here.
Phase 5 (V5–V7) builds on exactly this state; nothing in this file authorizes a change.

**Migration authority:** `backend/src/main/resources/db/migration/` — Flyway enabled only on
the PostgreSQL profile (`application-postgres.yml`: `ddl-auto: validate`, `flyway.enabled: true`).
The default H2 profile runs `ddl-auto: update` with Flyway disabled. Flyway history at baseline:
`V1..V4` (four applied migrations, no V5+).

## Flyway history

| Version | File | Purpose (from its own header) |
|---|---|---|
| V1 | `V1__baseline_schema.sql` | Hand-reviewed PostgreSQL representation of the accepted Phase 3 entity model; deterministic constraint names; nullable branch-ownership legacy seam |
| V2 | `V2__phase4_constraints.sql` | Lifecycle/status check domains, duration range, non-empty code checks; forward-only, no data rewrite |
| V3 | `V3__branch_time_zone_and_typed_workflow_values.sql` | `branches.time_zone` (IANA, backfilled only for the three known demo fixtures), demonstrated workflow columns varchar→timestamptz via owning-branch zone (guards fail closed on unknown/zone-less rows), `invoices.amount` → `numeric(19,2)` |
| V4 | `V4__scope_and_concurrency_constraints.sql` | Partial unique index for active appointment windows; exact-duplicate availability uniqueness backstop; MRN/invoice-number uniqueness stays global |

## Core hierarchy tables (V1, amended V2/V3)

- `hospital_organizations` — `id uuid pk`, `code varchar(255) not null` (`uk_hospital_organizations_code`, `ck_hospital_organizations_code_not_empty`), `name`, `created_at/updated_at timestamptz(6)`, `version bigint`. **Exactly one synthetic row is the Phase 4/5 network boundary (`DEMO-ORG-001` seed).**
- `branches` — `organization_id uuid not null fk→hospital_organizations` (`fk_branches_organization`), `code` (`uk_branches_organization_code (organization_id, code)`, `ck_branches_code_not_empty`), `name`, `location_label`, `active boolean not null`, `time_zone varchar(60)` (V3; populated only for `DEMO-BR-001`/`002`/`003`), audit/version columns.
- `departments` — nullable `branch_id` (`fk_departments_branch`), `uk_departments_branch_code (branch_id, code)`.

## Auth tables (V1)

- `user_accounts` — `username` unique (`uk_user_accounts_username`), `password_hash`, `enabled`.
- `user_account_roles` — join rows; `roles` check domain `('ADMIN','DOCTOR','NURSE','RECEPTIONIST','LAB_TECH','RADIOLOGY_TECH','PHARMACIST','BILLING','HR','STAFF')` (`ck_user_account_roles_role`).
- `acting_assignments` — `account_id fk`, `organization_id not null fk`, nullable `branch_id`/`department_id`, `role` + `scope` check domains (`ck_acting_assignments_role`; `scope in ('ORGANIZATION','BRANCH','DEPARTMENT')` via `ck_acting_assignments_scope`), `enabled`. Logical uniqueness: `uk_acting_assignments_logical (account_id, role, branch_id, department_id)` — **null-sensitive (PostgreSQL treats NULLs as distinct); Phase 5 T022 replaces this with a NULL-safe strategy.**

## Ownership columns (the Phase 5 migration seam)

- Nullable branch-ownership columns by design (V1 header): `patients.branch_id`, `beds.branch_id`, `appointments.branch_id`, `admissions.branch_id`, `emergency_visits.branch_id`, `invoices.branch_id`, `staff_members.branch_id`, `departments.branch_id` (nullable FK), `staff_availability.branch_id` (not null, no FK in V1). Null = legacy pre-Phase-3 row, never guessed/backfilled, invisible through scoped endpoints.
- `patients.branch_id` (`fk_patients_branch`) — Phase 5 moves authority to organization/network + hospital access; today every service read is branch-scoped.
- `audit_events` context columns (V1): `assignment_id`, `role` + `scope` check domains (`ck_audit_events_role`, `ck_audit_events_scope`), `organization_id`, `branch_id`, `department_id` — all nullable; **no hospital column exists yet (Phase 5 T026 adds it).**

## Demonstrated workflow tables (V1–V3)

- `appointments` — `branch_id`, `patient_id varchar(255)`, `professional_id`, `scheduled_at`/`ends_at` **timestamptz after V3** (were varchar), `duration_minutes` check `5..480 or null` (`ck_appointments_duration_range`), `status` check `('scheduled','confirmed','completed','cancelled')` (`ck_appointments_status`); index `idx_appointments_branch_professional`; partial unique `uq_appointments_active_window (branch_id, professional_id, scheduled_at) where status <> 'cancelled' and ends_at is not null` (V4).
- `admissions` — `branch_id` (no FK in V1), `patient_id`, `admitted_at`/`discharged_at` timestamptz after V3, `status` check `('ADMITTED','DISCHARGED')` (`ck_admissions_status`); index `idx_admissions_branch`.
- `admission_bed_assignments` — `admission_id`/`bed_id` (no FKs in V1), unique `uq_assignment_active_admission (admission_id)` and `uq_assignment_active_bed (bed_id)` — the one-active-assignment invariant.
- `emergency_visits` — `branch_id`, `arrival_at` timestamptz after V3, `status` check `('WAITING','IN_TREATMENT','CLOSED')` (`ck_emergency_visits_status`); index `idx_emergency_visits_branch`.
- `invoices` — `branch_id`, `invoice_number` unique (`uk_invoices_invoice_number`), `amount numeric(19,2)` after V3, `status` check `('DRAFT','ISSUED','PAID','VOID')` (`ck_invoices_status`); index `idx_invoices_branch`.
- `beds` — `branch_id` (`fk_beds_branch`), `ward`/`room`/`bed_number`, `occupancy_status` check `('AVAILABLE','MAINTENANCE','OUT_OF_SERVICE','OCCUPIED')` (`ck_beds_occupancy_status`), `patient_id varchar(255)`; unique `uq_bed_branch_ward_room_number (branch_id, ward, room, bed_number)`.
- `staff_members`, `staff_availability` — availability is `timestamp(6)` without zone (deliberate, V1 header); unique `uq_availability_branch_staff_window (branch_id, staff_member_id, starts_at, ends_at)` (V4); index `idx_staff_availability_branch_professional`.
- `patients` — `branch_id`, `medical_record_number` unique (`uk_patients_medical_record_number` + unique index `idx_patient_mrn` — globally unique inside the one network, confirmed unchanged by V4), `full_name`, optional PII columns, `active`.

## Accepted raw operational families (V1, raw CRUD, no scope columns)

`blood_units`, `clinical_encounters`, `diet_orders`, `document_records`, `drugs`,
`insurance_claims`, `inventory_items`, `lab_orders`, `medication_orders`, `notifications`,
`nursing_observations` (including the legacy `temperaturec` column artifact), `radiology_orders`,
`shifts`, `surgical_cases`, `work_orders` — each with uuid pk, BaseEntity columns
(`created_at/updated_at timestamptz(6)`, `version bigint`), and no branch/organization ownership
column except where noted above.

## Audit evidence indexes (V1)

`idx_audit_events_organization_occurred (organization_id, occurred_at)`,
`idx_audit_events_branch_occurred (branch_id, occurred_at)`,
`idx_audit_events_department_occurred (department_id, occurred_at)` — the bounded
evidence-read paths Phase 5 must preserve while adding hospital context.

## Invariants Phase 5 must respect (inventory conclusions)

1. Forward-only Flyway; `ddl-auto=validate` on PostgreSQL — V5/V6/V7 must keep Hibernate
   validation green without touching V1–V4.
2. Branch-ownership nulls are a deliberate legacy seam — Phase 5 backfills must stay
   deterministic (known fixture codes), fail closed on unknown/ambiguous rows, and never
   mass-guess (V3 guard pattern is the model).
3. `uk_acting_assignments_logical` is null-sensitive — the known Phase 5 uniqueness repair point.
4. MRN/invoice uniqueness is globally scoped to the one network — confirmed deliberate (V4 header).
5. All timestamp conversions must reuse the owning-branch IANA zone discipline introduced in V3.
