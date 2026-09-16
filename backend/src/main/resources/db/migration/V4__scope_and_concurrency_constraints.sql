-- V4__scope_and_concurrency_constraints.sql
-- Phase 5 scope and concurrency invariants (plan Task 5; FR-004).
--
-- Forward-only database backstops for the demonstrated concurrency
-- contracts. The service pre-checks stay authoritative; these indexes make
-- the racing loser fail at the database instead of ever persisting a
-- contradiction.
--
-- Uniqueness decision (plan Task 5, step 3; FR-014): MRN (patients) and
-- invoice number (invoices) remain GLOBALLY unique — the V1 unique
-- constraints are unchanged. No evidence justifies widening them to
-- branch scope, and no migration of existing identifiers is performed.

-- Exact-duplicate racing creates for one professional's window are never
-- two legal rows. Cancelled creates claim nothing, and legacy rows without
-- a computed window claim nothing (mirrors the half-open overlap contract).
create unique index uq_appointments_active_window
    on appointments (branch_id, professional_id, scheduled_at)
    where status <> 'cancelled' and ends_at is not null;

-- An exact duplicate availability interval is never legal; adjacency is.
-- This is the deterministic backstop behind the availability overlap
-- pre-check under a racing double-insert.
create unique index uq_availability_branch_staff_window
    on staff_availability (branch_id, staff_member_id, starts_at, ends_at);
