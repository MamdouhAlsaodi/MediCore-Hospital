# Phase 5 Research and Architecture Decisions

## Decision 1 — Preserve the network boundary; insert a hospital level

**Decision:** Keep `HospitalOrganization` as the single enterprise network and add `HospitalFacility`; change `Branch` ownership from direct organization-only semantics to `HospitalFacility → HospitalOrganization` while preserving an organization consistency backstop.

**Rationale:** This is additive, keeps existing organization IDs and assignments meaningful, and avoids renaming a foundational aggregate. A direct organization-to-branch relationship may remain temporarily during migration but must not become an alternate source of truth after backfill.

**Rejected alternatives:**
- Rename `HospitalOrganization` to `HospitalNetwork`: high churn with little value.
- Treat each existing organization as one hospital and add a new tenant above it: accidentally approaches SaaS tenancy and rewrites authority.
- Store hospital as a string on branch: cannot enforce hierarchy or lifecycle.

## Decision 2 — Add hospital scope without replacing organization scope

**Decision:** Add `HOSPITAL` to `AssignmentScope`; preserve `ORGANIZATION` as network-level scope for wire compatibility. Add `hospitalId` to `ActingContext` and JWT structural claims. Every valid context still binds one concrete branch.

**Rationale:** Existing authorization already reloads assignments and structural claims server-side. Extending the chain is safer than creating a parallel permission system.

**Invariant:** `organizationId`, `hospitalId`, `branchId`, and optional `departmentId` must form one valid active ancestor chain on every request.

## Decision 3 — Network identity plus explicit hospital access

**Decision:** Move patient authority to the network and add `PatientHospitalAccess(patient_id, hospital_id, status, source, transfer_id?)`.

**Rationale:** A global patient row alone would leak identities; branch-owned copies would defeat network identity. An explicit grant provides a testable visibility boundary.

**Migration:** For every legacy patient, derive the hospital from its valid legacy branch and create exactly one active access row. Unknown/mismatched ownership blocks migration.

**Search behavior:** Hospital-scoped search joins through active access rows. Network-scoped patient search is restricted to an explicit network-level role and remains synthetic.

## Decision 4 — Keep transfer orchestration synchronous and transactional

**Decision:** Implement `TransferRequest` as a modular-monolith aggregate with optimistic versioning and database constraints. Use a dedicated `TransferBedReservation` with exclusive active ownership.

**Rationale:** Source and destination are in one database. A message broker/outbox/saga would add failure modes without a current external consumer.

**Rejected alternative:** Microservices or event broker. Deferred until a real external integration requires asynchronous delivery.

## Decision 5 — Explicit transfer transition matrix

| Current | Command | Required side | Next | Required atomic effects |
|---|---|---|---|---|
| — | request | source | REQUESTED | create request + source audit |
| REQUESTED | accept | destination | ACCEPTED | validate destination + reserve bed + grant patient access + audit |
| REQUESTED | reject | destination | REJECTED | reason code + audit |
| REQUESTED | cancel | source | CANCELLED | audit |
| ACCEPTED | cancel | source or destination policy role | CANCELLED | release owned reservation + audit |
| ACCEPTED | start transit | source | IN_TRANSIT | mark source handoff + release source bed according to existing admission transition + audit |
| IN_TRANSIT | complete | destination | COMPLETED | create/bind destination admission + occupy reserved bed + finalize + audit |

All other transitions return `409`. No transition trusts source/destination IDs from the client after aggregate creation.

## Decision 6 — Bounded idempotency at command boundary

**Decision:** Require `Idempotency-Key` for transfer mutations. Store key, authenticated actor/assignment, operation, aggregate ID, SHA-256 request fingerprint, terminal HTTP status, and response resource ID.

**Rationale:** Retries are expected around state-changing workflows. Identical retries must return the prior semantic result; changed payload under the same key must conflict.

**Boundary:** No global generic idempotency framework. Scope it to transfer commands first.

## Decision 7 — Capacity is derived; reservations are persisted

**Decision:** Existing bed state plus active transfer reservations drive availability. Capacity APIs aggregate grouped counts from an explicit server-derived branch set. Scheduling remains branch-owned and uses existing half-open overlap semantics.

**Rationale:** Persisting aggregate capacity would create drift. Persist only the exclusive reservation fact.

## Decision 8 — Command center uses bounded grouped queries

**Decision:** Extend `DashboardService` or split a narrow `NetworkOperationsDashboardService` when its responsibilities become too broad. Aggregate branch rows into hospitals and network totals with bounded query count and deterministic ordering.

**Rationale:** Existing grouped repository metrics are a strong base, but hospital grouping and transfer metrics should not turn one class into an unbounded god service.

## Decision 9 — Audit context is expanded, not duplicated

**Decision:** Add `hospitalId`, and for transfer events include bounded source/destination hospital and branch IDs in dedicated columns or a strict transfer evidence DTO. Never place patient names/contact/request bodies in audit details.

**Rationale:** Free-form JSON/details would weaken indexing and leakage controls. Structured bounded fields preserve traceability.

## Decision 10 — Contract-first additive API

Planned route families:

- `GET /api/network/hierarchy`
- `GET /api/hospitals/{hospitalId}` (authorized non-enumerating read)
- `POST /api/auth/context` extended with target hospital/branch
- Existing patient routes become access-grant-aware
- `GET|POST /api/transfers`
- `GET /api/transfers/{id}`
- `POST /api/transfers/{id}/accept|reject|cancel|start-transit|complete`
- `GET /api/capacity/branch`
- `GET /api/capacity/hospital`
- `GET /api/capacity/network`
- `GET /api/dashboard/hospital`
- `GET /api/dashboard/network` extended to hospital summaries

Use stable `ApiError`; conflicts remain `409`; unauthorized foreign identifiers remain generic `403/404` according to the existing non-enumeration convention.

## Decision 11 — Migration sequence

1. Create hospitals table and one deterministic legacy hospital.
2. Add nullable `branches.hospital_id`; backfill only branches whose organization is valid.
3. Verify organization consistency; make `hospital_id` non-null; add FK/index/unique constraints.
4. Add `hospital_id` to assignments/audit as nullable migration seams; derive/backfill where unambiguous.
5. Add patient organization ownership and patient-hospital access; backfill from branch.
6. Add transfer/reservation/idempotency tables.
7. Validate with a Phase 4-shaped PostgreSQL fixture, restart idempotency, and negative malformed fixtures.

Unknown data must block; no host defaults or nearest-hospital guessing.

## Decision 12 — Quality and acceptance

- RED → GREEN → REFACTOR for behavior changes.
- Real PostgreSQL for migrations, constraints, locking, concurrency, and idempotency.
- H2 may remain for fast unit/API coverage but cannot substantiate PostgreSQL claims.
- Generated OpenAPI/client drift gate.
- Browser context discrimination at render phase, desktop and mobile.
- Canonical fail-fast Phase 5 acceptance with capability-negative proof and resource cleanup.
- Documentation remains truthful: synthetic Training/Portfolio, non-clinical, no production/compliance claim.
