-- V2__phase4_constraints.sql
-- Phase 4 forward-only constraints (plan Task 2, step 4).
--
-- Deterministic lifecycle/ownership constraints that encode the accepted
-- Phase 3 invariants and are supported by the accepted synthetic data
-- (every status value written by the services and the synthetic seeder is
-- inside the domains below). Forward-only: no data is rewritten here.
-- Branch-ownership columns stay nullable by design (legacy rows are never
-- guessed, adopted, or mass-updated); enforcing non-null ownership on
-- unknown rows is explicitly out of scope for this phase.

-- Appointment lifecycle (docs/plan3.md §4.6 contract: lowercase states).
alter table appointments
    add constraint ck_appointments_status
    check (status in ('scheduled', 'confirmed', 'completed', 'cancelled'));

-- Appointment duration: bounded engineering validation range (5-480) or
-- the pre-Task-9 legacy null (no computable window).
alter table appointments
    add constraint ck_appointments_duration_range
    check (duration_minutes is null or duration_minutes between 5 and 480);

-- Admission lifecycle is server-owned: ADMITTED -> DISCHARGED only.
alter table admissions
    add constraint ck_admissions_status
    check (status in ('ADMITTED', 'DISCHARGED'));

-- Emergency-visit lifecycle: WAITING -> IN_TREATMENT | CLOSED, CLOSED terminal.
alter table emergency_visits
    add constraint ck_emergency_visits_status
    check (status in ('WAITING', 'IN_TREATMENT', 'CLOSED'));

-- Invoice lifecycle (financial simulation only): DRAFT -> ISSUED | VOID,
-- ISSUED -> PAID | VOID; PAID and VOID terminal.
alter table invoices
    add constraint ck_invoices_status
    check (status in ('DRAFT', 'ISSUED', 'PAID', 'VOID'));

-- Bed occupancy domain: three client-manageable states plus the
-- admission-owned OCCUPIED state.
alter table beds
    add constraint ck_beds_occupancy_status
    check (occupancy_status in ('AVAILABLE', 'MAINTENANCE', 'OUT_OF_SERVICE', 'OCCUPIED'));

-- Branch lifecycle/state sanity: human codes are identifiers only and are
-- never authorization evidence; the constraint keeps them non-empty.
alter table branches
    add constraint ck_branches_code_not_empty
    check (length(trim(code)) > 0);

alter table hospital_organizations
    add constraint ck_hospital_organizations_code_not_empty
    check (length(trim(code)) > 0);
