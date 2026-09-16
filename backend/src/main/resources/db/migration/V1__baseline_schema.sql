-- V1__baseline_schema.sql
-- Phase 4 baseline schema authority (plan Task 2, step 3).
--
-- Hand-reviewed representation of the accepted Phase 3 entity model
-- (Spring Boot 3.5.5 / Hibernate 6.6 naming and types on PostgreSQL),
-- NOT a blind generated dump. Review notes:
--   * Table/column names follow the shipped CamelCaseToUnderscores naming
--     exactly as Hibernate validates them (including legacy artifacts of
--     that naming such as nursing_observations.temperaturec).
--   * Primary keys, foreign keys, unique constraints, and enum-domain
--     checks are named deterministically (the entity @UniqueConstraint
--     names are preserved verbatim: uk_branches_organization_code,
--     uq_assignment_active_admission, uq_assignment_active_bed,
--     uq_bed_branch_ward_room_number, uk_departments_branch_code,
--     uk_invoices_invoice_number).
--   * Timestamps for BaseEntity/Instant fields are timestamptz(6);
--     staff_availability local windows are timestamp(6) without zone
--     (typed migration of the demonstrated workflow columns arrives in V3).
--   * Branch ownership columns on workflow rows stay nullable: null is the
--     deliberate legacy seam for pre-Phase-3 rows, which remain invisible
--     through branch-scoped endpoints. They are never backfilled by guess.
--   * Indexes cover the demonstrated branch-scoped read paths and the
--     audit evidence queries; the patients MRN index mirrors the shipped
--     @Index(name="idx_patient_mrn").

-- ---------------------------------------------------------------- hierarchy
create table hospital_organizations (
    id          uuid         not null constraint pk_hospital_organizations primary key,
    code        varchar(255) not null,
    name        varchar(255) not null,
    created_at  timestamptz(6) not null,
    updated_at  timestamptz(6) not null,
    version     bigint       not null,
    constraint uk_hospital_organizations_code unique (code)
);

create table branches (
    id             uuid         not null constraint pk_branches primary key,
    organization_id uuid        not null constraint fk_branches_organization references hospital_organizations,
    code           varchar(255) not null,
    name           varchar(255) not null,
    location_label varchar(255) not null,
    active         boolean      not null,
    created_at     timestamptz(6) not null,
    updated_at     timestamptz(6) not null,
    version        bigint       not null,
    constraint uk_branches_organization_code unique (organization_id, code)
);

-- -------------------------------------------------------------- department
create table departments (
    id         uuid         not null constraint pk_departments primary key,
    branch_id  uuid                  constraint fk_departments_branch references branches,
    code       varchar(255),
    name       varchar(255),
    specialty  varchar(255),
    location   varchar(255),
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    version    bigint       not null,
    constraint uk_departments_branch_code unique (branch_id, code)
);

-- ------------------------------------------------------------------- auth
create table user_accounts (
    id            uuid         not null constraint pk_user_accounts primary key,
    username      varchar(255) not null,
    password_hash varchar(255) not null,
    enabled       boolean      not null,
    created_at    timestamptz(6) not null,
    updated_at    timestamptz(6) not null,
    version       bigint       not null,
    constraint uk_user_accounts_username unique (username)
);

create table user_account_roles (
    user_account_id uuid         not null constraint fk_user_account_roles_account references user_accounts,
    roles           varchar(255) constraint ck_user_account_roles_role
                    check (roles in ('ADMIN','DOCTOR','NURSE','RECEPTIONIST','LAB_TECH',
                                     'RADIOLOGY_TECH','PHARMACIST','BILLING','HR','STAFF'))
);

create table acting_assignments (
    id              uuid         not null constraint pk_acting_assignments primary key,
    account_id      uuid         not null constraint fk_acting_assignments_account references user_accounts,
    organization_id uuid         not null constraint fk_acting_assignments_organization references hospital_organizations,
    branch_id       uuid                  constraint fk_acting_assignments_branch references branches,
    department_id   uuid                  constraint fk_acting_assignments_department references departments,
    role            varchar(255) not null constraint ck_acting_assignments_role
                    check (role in ('ADMIN','DOCTOR','NURSE','RECEPTIONIST','LAB_TECH',
                                    'RADIOLOGY_TECH','PHARMACIST','BILLING','HR','STAFF')),
    scope           varchar(255) not null constraint ck_acting_assignments_scope
                    check (scope in ('ORGANIZATION','BRANCH','DEPARTMENT')),
    enabled         boolean      not null,
    created_at      timestamptz(6) not null,
    updated_at      timestamptz(6) not null,
    version         bigint       not null,
    constraint uk_acting_assignments_logical unique (account_id, role, branch_id, department_id)
);

-- ----------------------------------------------------------------- patient
create table patients (
    id                    uuid         not null constraint pk_patients primary key,
    branch_id             uuid                  constraint fk_patients_branch references branches,
    medical_record_number varchar(255) not null,
    full_name             varchar(255) not null,
    date_of_birth         date,
    sex                   varchar(255),
    phone                 varchar(255),
    email                 varchar(255),
    national_id           varchar(255),
    address               varchar(1000),
    active                boolean      not null,
    created_at            timestamptz(6) not null,
    updated_at            timestamptz(6) not null,
    version               bigint       not null,
    constraint uk_patients_medical_record_number unique (medical_record_number)
);

create unique index idx_patient_mrn on patients (medical_record_number);

-- ------------------------------------------------------------------- staff
create table staff_members (
    id             uuid         not null constraint pk_staff_members primary key,
    branch_id      uuid                  constraint fk_staff_members_branch references branches,
    employee_code  varchar(255),
    full_name      varchar(255),
    profession     varchar(255),
    license_number varchar(255),
    department     varchar(255),
    created_at     timestamptz(6) not null,
    updated_at     timestamptz(6) not null,
    version        bigint       not null
);

create table staff_availability (
    id              uuid         not null constraint pk_staff_availability primary key,
    branch_id       uuid         not null,
    staff_member_id uuid         not null,
    starts_at       timestamp(6) not null,
    ends_at         timestamp(6) not null,
    created_at      timestamptz(6) not null,
    updated_at      timestamptz(6) not null,
    version         bigint       not null
);

create index idx_staff_availability_branch_professional
    on staff_availability (branch_id, staff_member_id, starts_at, ends_at);

-- -------------------------------------------------------------------- beds
create table beds (
    id               uuid         not null constraint pk_beds primary key,
    branch_id        uuid                  constraint fk_beds_branch references branches,
    ward             varchar(255) not null,
    room             varchar(255) not null,
    bed_number       varchar(255) not null,
    occupancy_status varchar(255) not null,
    patient_id       varchar(255),
    created_at       timestamptz(6) not null,
    updated_at       timestamptz(6) not null,
    version          bigint       not null,
    constraint uq_bed_branch_ward_room_number unique (branch_id, ward, room, bed_number)
);

-- --------------------------------------------------- demonstrated workflow
create table appointments (
    id               uuid         not null constraint pk_appointments primary key,
    branch_id        uuid                  constraint fk_appointments_branch references branches,
    patient_id       varchar(255),
    professional_id  varchar(255),
    scheduled_at     varchar(255),
    duration_minutes integer,
    ends_at          varchar(255),
    type             varchar(255),
    status           varchar(255),
    created_at       timestamptz(6) not null,
    updated_at       timestamptz(6) not null,
    version          bigint       not null
);

create index idx_appointments_branch_professional
    on appointments (branch_id, professional_id);

create table admissions (
    id            uuid         not null constraint pk_admissions primary key,
    branch_id     uuid,
    patient_id    varchar(255),
    admitted_at   varchar(255),
    discharged_at varchar(255),
    reason        varchar(255),
    status        varchar(255),
    created_at    timestamptz(6) not null,
    updated_at    timestamptz(6) not null,
    version       bigint       not null
);

create index idx_admissions_branch on admissions (branch_id);

create table admission_bed_assignments (
    id           uuid not null constraint pk_admission_bed_assignments primary key,
    admission_id uuid not null,
    bed_id       uuid not null,
    created_at   timestamptz(6) not null,
    updated_at   timestamptz(6) not null,
    version      bigint not null,
    constraint uq_assignment_active_admission unique (admission_id),
    constraint uq_assignment_active_bed unique (bed_id)
);

create table emergency_visits (
    id              uuid         not null constraint pk_emergency_visits primary key,
    branch_id       uuid,
    patient_id      varchar(255),
    arrival_at      varchar(255),
    triage_level    varchar(255),
    chief_complaint varchar(255),
    status          varchar(255),
    created_at      timestamptz(6) not null,
    updated_at      timestamptz(6) not null,
    version         bigint       not null
);

create index idx_emergency_visits_branch on emergency_visits (branch_id);

create table invoices (
    id             uuid         not null constraint pk_invoices primary key,
    branch_id      uuid,
    patient_id     varchar(255),
    invoice_number varchar(255),
    amount         varchar(255),
    currency       varchar(255),
    status         varchar(255),
    created_at     timestamptz(6) not null,
    updated_at     timestamptz(6) not null,
    version        bigint       not null,
    constraint uk_invoices_invoice_number unique (invoice_number)
);

create index idx_invoices_branch on invoices (branch_id);

-- ------------------------------------------------------------------- audit
create table audit_events (
    id             uuid         not null constraint pk_audit_events primary key,
    actor          varchar(255) not null,
    action         varchar(255) not null,
    resource_type  varchar(255),
    resource_id    varchar(255),
    details        varchar(2000),
    occurred_at    timestamptz(6),
    correlation_id varchar(64),
    assignment_id  uuid,
    role           varchar(255) constraint ck_audit_events_role
                   check (role in ('ADMIN','DOCTOR','NURSE','RECEPTIONIST','LAB_TECH',
                                   'RADIOLOGY_TECH','PHARMACIST','BILLING','HR','STAFF')),
    scope          varchar(255) constraint ck_audit_events_scope
                   check (scope in ('ORGANIZATION','BRANCH','DEPARTMENT')),
    organization_id uuid,
    branch_id      uuid,
    department_id  uuid,
    created_at     timestamptz(6) not null,
    updated_at     timestamptz(6) not null,
    version        bigint       not null
);

create index idx_audit_events_organization_occurred on audit_events (organization_id, occurred_at);
create index idx_audit_events_branch_occurred on audit_events (branch_id, occurred_at);
create index idx_audit_events_department_occurred on audit_events (department_id, occurred_at);

-- ------------------------------------- accepted raw operational families
-- These families stay as accepted in Phase 3 (raw CRUD, no scope widening);
-- the baseline only gives them their reviewed PostgreSQL representation.

create table blood_units (
    id          uuid not null constraint pk_blood_units primary key,
    unit_code   varchar(255),
    blood_type  varchar(255),
    component   varchar(255),
    expires_on  varchar(255),
    status      varchar(255),
    created_at  timestamptz(6) not null,
    updated_at  timestamptz(6) not null,
    version     bigint not null
);

create table clinical_encounters (
    id             uuid not null constraint pk_clinical_encounters primary key,
    patient_id      varchar(255),
    professional_id varchar(255),
    started_at      varchar(255),
    diagnosis       varchar(255),
    clinical_note   varchar(255),
    created_at      timestamptz(6) not null,
    updated_at      timestamptz(6) not null,
    version         bigint not null
);

create table diet_orders (
    id           uuid not null constraint pk_diet_orders primary key,
    patient_id   varchar(255),
    diet_type    varchar(255),
    restrictions varchar(255),
    instructions varchar(255),
    status       varchar(255),
    created_at   timestamptz(6) not null,
    updated_at   timestamptz(6) not null,
    version      bigint not null
);

create table document_records (
    id           uuid not null constraint pk_document_records primary key,
    owner_type   varchar(255),
    owner_id     varchar(255),
    file_name    varchar(255),
    content_type varchar(255),
    storage_key  varchar(255),
    created_at   timestamptz(6) not null,
    updated_at   timestamptz(6) not null,
    version      bigint not null
);

create table drugs (
    id           uuid not null constraint pk_drugs primary key,
    code         varchar(255),
    generic_name varchar(255),
    brand_name   varchar(255),
    strength     varchar(255),
    stock_quantity varchar(255),
    created_at   timestamptz(6) not null,
    updated_at   timestamptz(6) not null,
    version      bigint not null
);

create table insurance_claims (
    id            uuid not null constraint pk_insurance_claims primary key,
    patient_id    varchar(255),
    payer         varchar(255),
    policy_number varchar(255),
    claim_number  varchar(255),
    status        varchar(255),
    created_at    timestamptz(6) not null,
    updated_at    timestamptz(6) not null,
    version       bigint not null
);

create table inventory_items (
    id            uuid not null constraint pk_inventory_items primary key,
    sku           varchar(255),
    name          varchar(255),
    category      varchar(255),
    quantity      varchar(255),
    reorder_level varchar(255),
    created_at    timestamptz(6) not null,
    updated_at    timestamptz(6) not null,
    version       bigint not null
);

create table lab_orders (
    id         uuid not null constraint pk_lab_orders primary key,
    patient_id varchar(255),
    test_code  varchar(255),
    specimen   varchar(255),
    result     varchar(255),
    status     varchar(255),
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    version    bigint not null
);

create table medication_orders (
    id         uuid not null constraint pk_medication_orders primary key,
    patient_id varchar(255),
    drug_id    varchar(255),
    dose       varchar(255),
    route      varchar(255),
    frequency  varchar(255),
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    version    bigint not null
);

create table notifications (
    id         uuid not null constraint pk_notifications primary key,
    recipient  varchar(255),
    channel    varchar(255),
    subject    varchar(255),
    message    varchar(255),
    status     varchar(255),
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    version    bigint not null
);

create table nursing_observations (
    id             uuid not null constraint pk_nursing_observations primary key,
    patient_id     varchar(255),
    temperaturec   varchar(255),
    blood_pressure varchar(255),
    heart_rate     varchar(255),
    note           varchar(255),
    created_at     timestamptz(6) not null,
    updated_at     timestamptz(6) not null,
    version        bigint not null
);

create table radiology_orders (
    id          uuid not null constraint pk_radiology_orders primary key,
    patient_id  varchar(255),
    modality    varchar(255),
    body_region varchar(255),
    report      varchar(255),
    status      varchar(255),
    created_at  timestamptz(6) not null,
    updated_at  timestamptz(6) not null,
    version     bigint not null
);

create table shifts (
    id         uuid not null constraint pk_shifts primary key,
    staff_id   varchar(255),
    department varchar(255),
    starts_at  varchar(255),
    ends_at    varchar(255),
    status     varchar(255),
    created_at timestamptz(6) not null,
    updated_at timestamptz(6) not null,
    version    bigint not null
);

create table surgical_cases (
    id             uuid not null constraint pk_surgical_cases primary key,
    patient_id     varchar(255),
    procedure_name varchar(255),
    surgeon_id     varchar(255),
    scheduled_at   varchar(255),
    status         varchar(255),
    created_at     timestamptz(6) not null,
    updated_at     timestamptz(6) not null,
    version        bigint not null
);

create table work_orders (
    id          uuid not null constraint pk_work_orders primary key,
    area        varchar(255),
    category    varchar(255),
    description varchar(255),
    priority    varchar(255),
    status      varchar(255),
    created_at  timestamptz(6) not null,
    updated_at  timestamptz(6) not null,
    version     bigint not null
);
