-- V6__network_patient_identity.sql
-- Phase 5 US3 (specs/005 tasks T064): one network patient identity with
-- per-hospital access grants. Forward-only and deterministic. Every guard
-- FAILS CLOSED (the migration aborts and rolls back, leaving the V5 schema
-- intact) instead of guessing ownership:
--
--   1. patients.organization_id: nullable add -> deterministic backfill
--      from the registering branch's organization -> malformed-ownership
--      refusal (any patient whose organization is un-derivable blocks the
--      migration) -> NOT NULL -> organization FK + read index. The legacy
--      branch_id column stays only as the migration source; it is retired
--      as an authority by this migration's access backfill.
--   2. patient_hospital_access table (PatientHospitalAccess exactly per
--      specs/005 data-model.md, plus the stored FK-pinned organization
--      column that makes the same-organization invariant representable as
--      composite foreign keys, mirroring V5's
--      fk_branches_hospital_organization pattern).
--   3. Access backfill: every pre-existing patient receives exactly one
--      ACTIVE LEGACY_MIGRATION grant for the hospital of its registering
--      branch, with a deterministic grant id derived from the patient id,
--      inside the same transaction as the schema change.
--   4. Uniqueness: a partial unique index allows at most one ACTIVE grant
--      per (patient, hospital) — REVOKED history stays legal — plus the
--      hospital/patient read indexes and the status/source check bounds.
--
-- No clinical, retention, or production semantics; synthetic data only.

-- ------------------------------------------------- patient network ownership
alter table patients add column organization_id uuid;

update patients p
set organization_id = b.organization_id
from branches b
where p.branch_id = b.id;

-- Guard: any patient whose organization could not be derived (null branch,
-- or a missing branch row) is malformed ownership and blocks the migration
-- rather than being guessed.
do $$
begin
    if exists (select 1 from patients p where p.organization_id is null) then
        raise exception 'V6 blocked: a patient has no derivable organization ownership (null or missing registering branch)';
    end if;
end $$;

alter table patients alter column organization_id set not null;

alter table patients
    add constraint fk_patients_organization foreign key (organization_id)
    references hospital_organizations;

-- Unique pairs so the composite consistency FKs below are possible.
alter table patients
    add constraint uk_patients_id_organization unique (id, organization_id);

-- ------------------------------------------------------ access grant table
create table patient_hospital_access (
    id                 uuid           not null constraint pk_patient_hospital_access primary key,
    patient_id         uuid           not null constraint fk_access_patient references patients,
    hospital_id        uuid           not null constraint fk_access_hospital references hospitals,
    organization_id    uuid           not null,
    status             varchar(16)    not null,
    source             varchar(24)    not null,
    source_transfer_id uuid           null,
    created_at         timestamptz(6) not null,
    updated_at         timestamptz(6) not null,
    revoked_at         timestamptz(6) null,
    version            bigint         not null,
    constraint ck_patient_hospital_access_status check (status in ('ACTIVE', 'REVOKED')),
    constraint ck_patient_hospital_access_source
        check (source in ('LEGACY_MIGRATION', 'LOCAL_REGISTRATION', 'TRANSFER_ACCEPTED')),
    constraint ck_patient_hospital_access_revoked
        check ((status = 'ACTIVE' and revoked_at is null) or (status = 'REVOKED' and revoked_at is not null))
);

-- Same-organization invariant, pinned in the database: the grant's patient
-- and hospital must both belong to the row's organization.
alter table patient_hospital_access
    add constraint fk_access_patient_organization foreign key (patient_id, organization_id)
    references patients (id, organization_id);

alter table patient_hospital_access
    add constraint fk_access_hospital_organization foreign key (hospital_id, organization_id)
    references hospitals (id, organization_id);

-- ------------------------------------------------- deterministic backfill
-- Exactly one ACTIVE LEGACY_MIGRATION grant per pre-existing patient, for
-- the hospital of its registering branch. The id is a deterministic
-- function of the patient id.
insert into patient_hospital_access
    (id, patient_id, hospital_id, organization_id, status, source, created_at, updated_at, version)
select md5('phase5-patient-access:' || p.id::text)::uuid,
       p.id,
       b.hospital_id,
       p.organization_id,
       'ACTIVE',
       'LEGACY_MIGRATION',
       now(),
       now(),
       0
from patients p
join branches b on b.id = p.branch_id;

-- Guard: the backfill must have covered every patient exactly once.
do $$
begin
    if exists (
        select 1 from patients p
        where not exists (
            select 1 from patient_hospital_access a
            where a.patient_id = p.id and a.status = 'ACTIVE' and a.source = 'LEGACY_MIGRATION')
    ) then
        raise exception 'V6 blocked: a patient did not receive its legacy access grant';
    end if;
end $$;

-- --------------------------------------------------- uniqueness and reads
-- At most one ACTIVE grant per (patient, hospital); REVOKED history stays.
create unique index uq_patient_hospital_access_active
    on patient_hospital_access (patient_id, hospital_id)
    where status = 'ACTIVE';

create index idx_patient_hospital_access_hospital on patient_hospital_access (hospital_id);
create index idx_patient_hospital_access_patient on patient_hospital_access (patient_id);
create index idx_patients_organization on patients (organization_id);
