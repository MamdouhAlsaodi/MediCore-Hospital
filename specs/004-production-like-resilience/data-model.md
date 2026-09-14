# Phase 4 Data Model and Migration Decisions

## Scope

This document governs only demonstrated Phase 3 workflow entities and their production-like PostgreSQL representation. It does not normalize unrelated raw-CRUD families.

## Branch

Add `time_zone` as a non-null IANA zone identifier after deterministic synthetic backfill. Known demo branches receive explicit stable zones documented in fixtures. Unknown rows block non-null enforcement until reconciled; they are never guessed from host time.

## Demonstrated workflow typed values

- `appointments.scheduled_at`, `appointments.ends_at`: migrate from canonical ISO local strings to timezone-unambiguous timestamps represented by Java `Instant` at persistence/service boundaries; branch-local input is converted with explicit zone policy.
- `admissions.admitted_at`, `admissions.discharged_at`: migrate to `Instant`-compatible columns.
- `emergency_visits.arrival_time`: migrate to `Instant`-compatible column.
- `invoices.amount`: migrate from canonical decimal string to exact `numeric(19,2)` while retaining simulated/no-payment semantics.
- UUID reference strings in demonstrated workflows may become UUID columns only when a migration can prove every non-null value parses and resolves correctly. Unresolved unknown rows must fail rehearsal, not be coerced.

## Compatibility

Public JSON keeps the accepted Phase 3 string shapes. DTO mappers serialize instants as ISO-8601 strings and simulated money via `toPlainString()`. Database typing must not silently become a wire breaking change.

## Invariants

- Branch ownership remains non-null for demonstrated active rows.
- Cross-branch references remain impossible through service/repository boundaries.
- Bed/admission uniqueness and optimistic locking remain enforced.
- Appointment overlap semantics remain half-open.
- MRN and invoice number remain globally unique in Phase 4 unless an explicit migration and full compatibility proof changes that decision; default is no change.
- Migration scripts are forward-only and deterministic; no runtime repair of unknown production-like data.
