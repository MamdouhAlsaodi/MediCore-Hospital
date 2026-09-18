-- V5__hospital_network_hierarchy.sql
-- Phase 5 foundational hierarchy (plan Phase B; specs/005 data-model.md;
-- research.md Decisions 1 and 11; FR-001..FR-005 seams).
--
-- Forward-only and deterministic. Runs against an empty schema or the
-- accepted Phase 4-shaped data. Every guard FAILS CLOSED (the migration
-- aborts and rolls back, leaving the previous schema intact) instead of
-- guessing ownership:
--
--   1. hospitals table (HospitalFacility): network-owned, bounded code
--      unique inside the organization, synthetic display region label,
--      validated IANA zone, active default true.
--   2. One deterministic legacy hospital per organization
--      (LEGACY-HOSPITAL-001), id derived from the organization id so the
--      same Phase 4-shaped input always produces the same hospital row.
--   3. branches.hospital_id: nullable add -> deterministic backfill from the
--      branch's organization -> collision proof -> hospital-scoped code
--      uniqueness REPLACING the organization-scoped constraint -> composite
--      consistency FK pinning branch.organization == branch.hospital.
--      organization -> NOT NULL -> read index. The legacy organization_id
--      column stays as the migration compatibility seam.
--   4. acting_assignments.hospital_id: nullable add -> derive from the
--      assignment's branch / the department's branch -> malformed-shape and
--      duplicate guards -> HOSPITAL added to the scope domain, an exact
--      scope-shape check, and the null-sensitive
--      uk_acting_assignments_logical table constraint REPLACED by a
--      PostgreSQL-safe null-collapsing expression unique index (SQL NULLs
--      are distinct; the coalesce-to-sentinel key is not).
--   5. audit_events: nullable acting hospital column (derived only where
--      unambiguous from the row's branch) plus the bounded nullable
--      transfer context columns (source_hospital_id, destination_hospital_id,
--      transfer_id), and the hospital-scoped evidence-read index.
--
-- No clinical, retention, or production semantics; synthetic data only.

-- ------------------------------------------------------------ hospitals
create table hospitals (
    id              uuid           not null constraint pk_hospitals primary key,
    organization_id uuid           not null constraint fk_hospitals_organization references hospital_organizations,
    code            varchar(32)    not null,
    name            varchar(160)   not null,
    region_label    varchar(160)   not null,
    time_zone       varchar(60)    not null,
    active          boolean        not null default true,
    created_at      timestamptz(6) not null,
    updated_at      timestamptz(6) not null,
    version         bigint         not null,
    constraint uk_hospitals_organization_code unique (organization_id, code),
    constraint uk_hospitals_id_organization unique (id, organization_id),
    constraint ck_hospitals_code_not_empty check (length(trim(code)) > 0)
);

-- ------------------------------------------- deterministic legacy hospital
-- One legacy hospital per organization: the Phase 4-shaped synthetic
-- network's branches all map to it. The id is a deterministic function of
-- the organization id, so the same input data produces the identical row on
-- any database (proven by Phase5MigrationIntegrationTest).
insert into hospitals (id, organization_id, code, name, region_label, time_zone, active,
                       created_at, updated_at, version)
select md5('phase5-legacy-hospital:' || o.id::text)::uuid,
       o.id,
       'LEGACY-HOSPITAL-001',
       'Legacy Synthetic Hospital',
       'Legacy Region',
       'UTC',
       true,
       now(),
       now(),
       0
from hospital_organizations o;

-- ------------------------------------------------------- branch ownership
alter table branches add column hospital_id uuid;

update branches b
set hospital_id = h.id
from hospitals h
where h.organization_id = b.organization_id;

-- Guard: every branch must be mapped (a branch's organization always has a
-- legacy hospital, so a null here means an unexpected database state).
do $$
begin
    if exists (select 1 from branches where hospital_id is null) then
        raise exception 'V5 blocked: branches exist whose hospital ownership cannot be derived from their organization; unknown ownership is never guessed';
    end if;
    -- Guard: prove no (hospital, code) collisions BEFORE replacing the
    -- organization-scoped uniqueness with the hospital-scoped one.
    if exists (
        select 1 from branches
        group by hospital_id, code
        having count(*) > 1
    ) then
        raise exception 'V5 blocked: duplicate branch codes exist within the mapped legacy hospital; the hospital-scoped uniqueness is never created over colliding data';
    end if;
end $$;

alter table branches drop constraint uk_branches_organization_code;
alter table branches add constraint uk_branches_hospital_code unique (hospital_id, code);

-- The composite consistency FK makes branch.organization divergence from
-- branch.hospital.organization unrepresentable at the database level
-- (hospitals carries the matching uk_hospitals_id_organization unique key).
alter table branches
    add constraint fk_branches_hospital foreign key (hospital_id) references hospitals,
    add constraint fk_branches_hospital_organization
        foreign key (hospital_id, organization_id) references hospitals (id, organization_id);

alter table branches alter column hospital_id set not null;

create index idx_branches_hospital on branches (hospital_id);

-- ------------------------------------------------- assignment hospital scope
alter table acting_assignments add column hospital_id uuid;

-- Derive from the assignment's own branch...
update acting_assignments a
set hospital_id = b.hospital_id
from branches b
where a.branch_id = b.id
  and a.scope = 'BRANCH';

-- ...and from a DEPARTMENT assignment's department branch.
update acting_assignments a
set hospital_id = b.hospital_id
from departments d
join branches b on b.id = d.branch_id
where a.department_id = d.id
  and a.scope = 'DEPARTMENT';

-- Guards: refuse malformed shapes and duplicates BEFORE any constraint
-- replacement, exactly like the V3 fail-closed pattern.
do $$
begin
    if exists (
        select 1 from acting_assignments a
        where (a.scope = 'ORGANIZATION'
                  and (a.hospital_id is not null or a.branch_id is not null or a.department_id is not null))
           or (a.scope = 'BRANCH'
                  and (a.branch_id is null or a.department_id is not null))
           or (a.scope = 'DEPARTMENT'
                  and (a.department_id is null or a.branch_id is not null))
    ) then
        raise exception 'V5 blocked: acting_assignments hold malformed scope shapes; unknown or mismatched assignment ownership is never guessed';
    end if;
    if exists (
        select 1 from acting_assignments a
        where a.scope in ('BRANCH', 'DEPARTMENT') and a.hospital_id is null
    ) then
        raise exception 'V5 blocked: BRANCH/DEPARTMENT assignments exist whose hospital ownership cannot be derived (branchless department); unknown ownership is never guessed';
    end if;
    if exists (
        select 1 from (
            select account_id, role, scope,
                   coalesce(hospital_id, '00000000-0000-0000-0000-000000000000'::uuid) as hospital_key,
                   coalesce(branch_id,  '00000000-0000-0000-0000-000000000000'::uuid) as branch_key,
                   coalesce(department_id, '00000000-0000-0000-0000-000000000000'::uuid) as department_key
            from acting_assignments
            group by 1, 2, 3, 4, 5, 6
            having count(*) > 1
        ) duplicates
    ) then
        raise exception 'V5 blocked: duplicate logical acting assignments exist; the null-safe uniqueness is never created over colliding data';
    end if;
end $$;

alter table acting_assignments drop constraint uk_acting_assignments_logical;

alter table acting_assignments drop constraint ck_acting_assignments_scope;
alter table acting_assignments
    add constraint ck_acting_assignments_scope
        check (scope in ('ORGANIZATION', 'HOSPITAL', 'BRANCH', 'DEPARTMENT')),
    add constraint ck_acting_assignments_scope_shape check (
        (scope = 'ORGANIZATION' and hospital_id is null     and branch_id is null     and department_id is null) or
        (scope = 'HOSPITAL'     and hospital_id is not null and branch_id is null     and department_id is null) or
        (scope = 'BRANCH'       and hospital_id is not null and branch_id is not null and department_id is null) or
        (scope = 'DEPARTMENT'   and hospital_id is not null and branch_id is null     and department_id is not null)),
    add constraint fk_acting_assignments_hospital foreign key (hospital_id) references hospitals;

-- PostgreSQL-safe replacement for the null-sensitive table constraint:
-- coalescing every nullable scope column to a fixed sentinel makes
-- organization/hospital duplicates a real unique violation, which SQL NULL
-- semantics (NULLs are distinct) never provided.
create unique index uq_acting_assignments_scope_logical
    on acting_assignments (account_id, role, scope,
        coalesce(hospital_id,    '00000000-0000-0000-0000-000000000000'::uuid),
        coalesce(branch_id,      '00000000-0000-0000-0000-000000000000'::uuid),
        coalesce(department_id,  '00000000-0000-0000-0000-000000000000'::uuid));

create index idx_acting_assignments_hospital on acting_assignments (hospital_id);

-- ------------------------------------------------------------- audit context
alter table audit_events
    add column hospital_id            uuid,
    add column source_hospital_id     uuid,
    add column destination_hospital_id uuid,
    add column transfer_id            uuid;

-- Derive the acting hospital only where the row's own branch makes it
-- unambiguous; legacy/unassigned rows keep null and are never guessed.
update audit_events a
set hospital_id = b.hospital_id
from branches b
where a.branch_id = b.id;

create index idx_audit_events_hospital_occurred on audit_events (hospital_id, occurred_at);
