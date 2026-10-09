-- V7__transfer_reservation_idempotency.sql
-- Phase 5 US4 (specs/005 tasks T081): the inter-hospital transfer lifecycle
-- schema — transfer_requests, transfer_bed_reservations, and
-- idempotency_records — with check bounds, foreign keys, read indexes, and
-- the partial unique index that allows at most one ACTIVE reservation per
-- bed. Forward-only, additive, deterministic, and fail-closed: every guard
-- aborts the migration instead of guessing ownership.
--
-- Synthetic operational data only; no clinical, retention, or production
-- semantics.

-- The parent id alone is not an ownership proof. These unique pairs are
-- PostgreSQL FK targets for each individually pinned hospital/branch/bed.
alter table branches
    add constraint uk_branches_id_hospital unique (id, hospital_id);

alter table beds
    add constraint uk_beds_id_branch unique (id, branch_id);

-- ------------------------------------------------------- transfer_requests
create table transfer_requests (
    id                          uuid           not null constraint pk_transfer_requests primary key,
    transfer_number             varchar(40)    not null,
    organization_id             uuid           not null,
    patient_id                  uuid           not null,
    source_hospital_id          uuid           not null,
    source_branch_id            uuid           not null,
    source_admission_id         uuid           null,
    destination_hospital_id     uuid           not null,
    destination_branch_id       uuid           null,
    destination_bed_id          uuid           null,
    status                      varchar(20)    not null,
    reason_code                 varchar(40)    not null,
    requested_by_assignment_id  uuid           not null,
    requested_at                timestamptz(6) not null,
    accepted_at                 timestamptz(6) null,
    transit_started_at          timestamptz(6) null,
    completed_at                timestamptz(6) null,
    cancelled_at                timestamptz(6) null,
    rejected_at                 timestamptz(6) null,
    created_at                  timestamptz(6) not null,
    updated_at                  timestamptz(6) not null,
    version                     bigint         not null,
    constraint ck_transfer_requests_status
        check (status in ('REQUESTED', 'ACCEPTED', 'IN_TRANSIT', 'COMPLETED', 'REJECTED', 'CANCELLED')),
    constraint ck_transfer_requests_reason check (length(reason_code) between 1 and 40),
    constraint ck_transfer_requests_source_destination
        check (source_hospital_id <> destination_hospital_id),
    -- Composite foreign keys skip any NULL component; never allow a bed to
    -- bypass its branch ownership by leaving destination_branch_id NULL.
    constraint ck_transfer_requests_bed_requires_branch
        check (destination_bed_id is null or destination_branch_id is not null)
);

-- One synthetic display number inside one organization.
alter table transfer_requests
    add constraint uk_transfer_requests_org_number
    unique (organization_id, transfer_number);

-- Same-organization invariant, pinned like V6: the transfer's patient and
-- both hospitals must belong to the row's organization.
alter table transfer_requests
    add constraint uk_transfer_requests_id_organization unique (id, organization_id);

alter table transfer_requests
    add constraint uk_transfer_requests_id_destination_bed unique (id, destination_bed_id);

alter table transfer_requests
    add constraint fk_transfer_requests_organization
    foreign key (organization_id) references hospital_organizations;

alter table transfer_requests
    add constraint fk_transfer_requests_patient_organization
    foreign key (patient_id, organization_id) references patients (id, organization_id);

alter table transfer_requests
    add constraint fk_transfer_requests_source_hospital_organization
    foreign key (source_hospital_id, organization_id) references hospitals (id, organization_id);

alter table transfer_requests
    add constraint fk_transfer_requests_destination_hospital_organization
    foreign key (destination_hospital_id, organization_id) references hospitals (id, organization_id);

alter table transfer_requests
    add constraint fk_transfer_requests_source_branch_hospital
    foreign key (source_branch_id, source_hospital_id) references branches (id, hospital_id);

alter table transfer_requests
    add constraint fk_transfer_requests_destination_branch_hospital
    foreign key (destination_branch_id, destination_hospital_id) references branches (id, hospital_id);

alter table transfer_requests
    add constraint fk_transfer_requests_destination_bed_branch
    foreign key (destination_bed_id, destination_branch_id) references beds (id, branch_id);

alter table transfer_requests
    add constraint fk_transfer_requests_source_admission
    foreign key (source_admission_id) references admissions;

-- Scoped reads and the visibility predicate.
create index idx_transfer_requests_org on transfer_requests (organization_id);
create index idx_transfer_requests_source_hospital on transfer_requests (source_hospital_id);
create index idx_transfer_requests_destination_hospital on transfer_requests (destination_hospital_id);
create index idx_transfer_requests_patient on transfer_requests (patient_id);

-- ------------------------------------------------- transfer_bed_reservations
create table transfer_bed_reservations (
    id          uuid           not null constraint pk_transfer_bed_reservations primary key,
    transfer_id uuid           not null,
    bed_id      uuid           not null,
    status      varchar(16)    not null,
    reserved_at timestamptz(6) not null,
    released_at timestamptz(6) null,
    created_at  timestamptz(6) not null,
    updated_at  timestamptz(6) not null,
    version     bigint         not null,
    constraint ck_transfer_bed_reservations_status check (status in ('ACTIVE', 'CONSUMED', 'RELEASED')),
    constraint ck_transfer_bed_reservations_released
        check ((status = 'ACTIVE' and released_at is null)
            or (status = 'RELEASED' and released_at is not null)
            or (status = 'CONSUMED' and released_at is null))
);

-- Exactly one reservation row per transfer (data-model constraint).
alter table transfer_bed_reservations
    add constraint uk_transfer_bed_reservations_transfer unique (transfer_id);

alter table transfer_bed_reservations
    add constraint fk_reservation_transfer_destination_bed
    foreign key (transfer_id, bed_id) references transfer_requests (id, destination_bed_id);

alter table transfer_bed_reservations
    add constraint fk_reservation_bed foreign key (bed_id) references beds;

-- The race backstop (FR-017): at most one ACTIVE reservation per bed.
-- RELEASED/CONSUMED history stays legal.
create unique index uq_transfer_bed_reservations_active_bed
    on transfer_bed_reservations (bed_id)
    where status = 'ACTIVE';

create index idx_transfer_bed_reservations_transfer on transfer_bed_reservations (transfer_id);

-- ------------------------------------------------------- idempotency_records
create table idempotency_records (
    id                  uuid           not null constraint pk_idempotency_records primary key,
    assignment_id       uuid           not null,
    operation           varchar(64)    not null,
    idempotency_key     varchar(128)   not null,
    request_fingerprint char(64)       not null,
    resource_type       varchar(64)    not null,
    resource_id         uuid           null,
    response_snapshot   text           null,
    http_status         integer        null,
    state               varchar(16)    not null,
    created_at          timestamptz(6) not null,
    completed_at        timestamptz(6) null,
    updated_at          timestamptz(6) not null,
    version             bigint         not null,
    constraint ck_idempotency_records_state check (state in ('IN_PROGRESS', 'COMPLETED')),
    constraint ck_idempotency_records_fingerprint check (length(request_fingerprint) = 64),
    constraint ck_idempotency_records_key check (length(idempotency_key) between 1 and 128),
    constraint ck_idempotency_records_completed
        check ((state = 'IN_PROGRESS' and completed_at is null and http_status is null)
            or (state = 'COMPLETED' and completed_at is not null and http_status is not null))
);

-- The normalized unique call identity (assignment, operation, key).
alter table idempotency_records
    add constraint uk_idempotency_records_key
    unique (assignment_id, operation, idempotency_key);

-- The acting assignment must be a real assignment row.
alter table idempotency_records
    add constraint fk_idempotency_records_assignment
    foreign key (assignment_id) references acting_assignments;

create index idx_idempotency_records_resource on idempotency_records (resource_type, resource_id);
