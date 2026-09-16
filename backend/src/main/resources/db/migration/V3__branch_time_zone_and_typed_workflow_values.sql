-- V3__branch_time_zone_and_typed_workflow_values.sql
-- Phase 4 typed branch time and demonstrated workflow values (plan Task 4;
-- data-model.md decisions; FR-012/FR-015).
--
-- Forward-only and deterministic:
--   1. branches gains time_zone (validated IANA id). Only the KNOWN
--      synthetic demo branch fixtures are backfilled, by their stable
--      business codes, with the fixture-table zones (main=UTC,
--      north=America/New_York, harbor=Asia/Tokyo). Unknown rows are never
--      guessed from host time.
--   2. The demonstrated workflow timestamp columns become timestamptz,
--      converted row-by-row through the owning branch's zone. Guards FIRST
--      reject any demonstrated row whose wall-clock value is non-empty but
--      whose branch is unknown (null) or zone-less — the migration FAILS on
--      unknown data instead of coercing it. Unparseable wall-clock values
--      fail the cast naturally (no silent coercion).
--   3. invoices.amount becomes exact numeric(19,2); a non-numeric value
--      fails the cast (fail closed). The demo canonical strings all carry
--      at most 2 fraction digits by contract.

-- ---------------------------------------------------------------- zones
alter table branches add column time_zone varchar(60);

update branches set time_zone = 'UTC'
    where code = 'DEMO-BR-001' and time_zone is null;
update branches set time_zone = 'America/New_York'
    where code = 'DEMO-BR-002' and time_zone is null;
update branches set time_zone = 'Asia/Tokyo'
    where code = 'DEMO-BR-003' and time_zone is null;

-- ------------------------------------------------------------- guards
-- Fail closed when a demonstrated workflow row would have to be guessed.

do $$
begin
    if exists (
        select 1 from appointments a
        left join branches b on b.id = a.branch_id
        where a.scheduled_at is not null and (a.branch_id is null or b.time_zone is null)
    ) then
        raise exception 'V3 blocked: appointments exist whose branch is unknown or has no time_zone; unknown legacy rows are never guessed';
    end if;
    if exists (
        select 1 from appointments a
        left join branches b on b.id = a.branch_id
        where a.ends_at is not null and (a.branch_id is null or b.time_zone is null)
    ) then
        raise exception 'V3 blocked: appointment end values exist whose branch is unknown or has no time_zone';
    end if;
    if exists (
        select 1 from admissions a
        left join branches b on b.id = a.branch_id
        where a.admitted_at is not null and (a.branch_id is null or b.time_zone is null)
    ) then
        raise exception 'V3 blocked: admissions exist whose branch is unknown or has no time_zone; unknown legacy rows are never guessed';
    end if;
    if exists (
        select 1 from admissions a
        left join branches b on b.id = a.branch_id
        where a.discharged_at is not null and (a.branch_id is null or b.time_zone is null)
    ) then
        raise exception 'V3 blocked: admission discharge values exist whose branch is unknown or has no time_zone';
    end if;
    if exists (
        select 1 from emergency_visits v
        left join branches b on b.id = v.branch_id
        where v.arrival_at is not null and (v.branch_id is null or b.time_zone is null)
    ) then
        raise exception 'V3 blocked: emergency visits exist whose branch is unknown or has no time_zone; unknown legacy rows are never guessed';
    end if;
end $$;

-- ------------------------------------------- typed demonstrated columns
-- The stored strings are branch-local wall-clock values; the conversion
-- pins them to instants through the owning branch's zone.

-- Two-step typed migration per column: add typed column, convert with the
-- branch zone, drop the legacy column, rename. A non-parsing value fails
-- the update (fail closed, no coercion).

alter table appointments add column scheduled_at_tz timestamptz;
update appointments a
    set scheduled_at_tz = a.scheduled_at::timestamp at time zone b.time_zone
    from branches b
    where b.id = a.branch_id and a.scheduled_at is not null;
alter table appointments drop column scheduled_at;
alter table appointments rename column scheduled_at_tz to scheduled_at;

alter table appointments add column ends_at_tz timestamptz;
update appointments a
    set ends_at_tz = a.ends_at::timestamp at time zone b.time_zone
    from branches b
    where b.id = a.branch_id and a.ends_at is not null;
alter table appointments drop column ends_at;
alter table appointments rename column ends_at_tz to ends_at;

alter table admissions add column admitted_at_tz timestamptz;
update admissions a
    set admitted_at_tz = a.admitted_at::timestamp at time zone b.time_zone
    from branches b
    where b.id = a.branch_id and a.admitted_at is not null;
alter table admissions drop column admitted_at;
alter table admissions rename column admitted_at_tz to admitted_at;

alter table admissions add column discharged_at_tz timestamptz;
update admissions a
    set discharged_at_tz = a.discharged_at::timestamp at time zone b.time_zone
    from branches b
    where b.id = a.branch_id and a.discharged_at is not null;
alter table admissions drop column discharged_at;
alter table admissions rename column discharged_at_tz to discharged_at;

alter table emergency_visits add column arrival_at_tz timestamptz;
update emergency_visits a
    set arrival_at_tz = a.arrival_at::timestamp at time zone b.time_zone
    from branches b
    where b.id = a.branch_id and a.arrival_at is not null;
alter table emergency_visits drop column arrival_at;
alter table emergency_visits rename column arrival_at_tz to arrival_at;

-- ------------------------------------------------ exact numeric amounts
alter table invoices
    alter column amount type numeric(19, 2)
    using amount::numeric(19, 2);
