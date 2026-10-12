# Phase 5 — Enterprise Network Architecture

> **Boundary:** This is an educational, non-clinical Training/Portfolio implementation over synthetic data. `HospitalOrganization` is one network boundary; multiple hospitals beneath it do not represent customer tenants. This document describes implemented paths and tested design, not production readiness, regulatory status, capacity, or SLA guarantees.

## Component and trust boundaries

```text
┌─────────────────────────────────────────────────────────────────────┐
│ React SPA                                                           │
│ context-keyed session/UI · patient and transfer features · summaries│
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS/JSON API; IDs are references only
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Spring Security / HTTP                                              │
│ JWT filter reloads subject + assignment + active hierarchy; routes  │
│ validate one acting role and concrete network/hospital/branch scope │
└──────────────────────────────┬──────────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Modular-monolith services                                           │
│ NetworkHierarchy · ActingContext/BranchAccess · Patient · Transfer  │
│ Capacity/Dashboard · Audit                                          │
│ Transfer commands run as database transactions; no broker/saga      │
└──────────────────────────────┬──────────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│ PostgreSQL (Flyway V1–V7)                                           │
│ network → hospital → branch → department                            │
│ patient + hospital access grants · transfer + bed reservation       │
│ idempotency records · audit context                                 │
└─────────────────────────────────────────────────────────────────────┘
```

Repositories and service methods derive allowed rows from the authenticated server context. Frontend selection and context-keyed rendering improve interaction safety but are not authorization controls. PostgreSQL constraints are an additional integrity/concurrency backstop; they do not replace service authorization.

## Hierarchy and acting authority

```text
HospitalOrganization (one synthetic network)
  ├── HospitalFacility A
  │     └── Branch → Department
  ├── HospitalFacility B
  │     └── Branch → Department
  └── ...

UserAccount → ActingAssignment (one role + one scope)
            → ActingContext { organizationId, hospitalId, branchId, departmentId? }
```

V5 adds `hospitals`, pins branches to their hospital, extends assignments with hospital scope, and records hospital context on audit rows. Existing `ORGANIZATION` assignment scope remains the network scope; `HOSPITAL`, `BRANCH`, and `DEPARTMENT` are narrower scopes. Each operational request resolves a concrete active hospital and branch, and optional department, from current server-side state. JWT claims carry structural pointers; they are compared with current assignment/hierarchy state and do not independently grant permissions. Hospital and branch activity and matching ancestry are checked in the service boundary. Hierarchy reads are limited to descendants authorized by the acting scope and use deterministic ordering.

The Phase 4-shaped V5 backfill maps existing branches to deterministic `LEGACY-HOSPITAL-001` within the organization and refuses malformed assignment shapes or collisions instead of guessing. This is a forward-only migration, not an automatic repair of arbitrary data.

## Patient identity and visibility

A patient row is one network identity. Hospital visibility is a separate fact represented by `PatientHospitalAccess`; hospital-scoped list/search/read/update operations require an active grant for the current hospital. V6 derives organization ownership from each legacy patient's branch and creates one active `LEGACY_MIGRATION` grant for the hospital that owns that branch. Unresolvable ownership blocks migration. Local registration creates the identity and local access; accepted transfer grants destination access. Grant creation and transfer state changes are coordinated in the transfer transaction so failure does not leave a partial success.

A shared identity does not imply unrestricted network-wide patient access. Another hospital without an active grant must not discover or operate on the patient; foreign identifiers receive the generic refusal/not-found behavior used by the API boundary.

## Transfer lifecycle and data flow

```text
Source hospital            Transfer service / PostgreSQL             Destination hospital
      │                              │                                        │
      ├─ request ───────────────────►│ REQUESTED                              │
      │                              │                                        │
      │                              │◄── accept + destination branch/bed ────┤
      │                              │ validate state/scope/bed                │
      │                              │ reserve bed + grant patient access      │
      │                              │ set ACCEPTED + audit (one transaction)  │
      ├─ start transit ─────────────►│ IN_TRANSIT                              │
      │                              │                                        │
      │                              │◄── complete ────────────────────────────┤
      │                              │ create/bind destination admission,      │
      │                              │ consume reservation, occupy bed,       │
      │                              │ complete + audit (one transaction)     │
```

The lifecycle is `REQUESTED → ACCEPTED → IN_TRANSIT → COMPLETED`; `REQUESTED` may instead become `REJECTED` or `CANCELLED`, and an `ACCEPTED` transfer may be cancelled under the implemented policy. Other transitions conflict. Mutating transfer endpoints require an `Idempotency-Key` (bounded to 128 characters in the current service contract). Repeating an identical command returns the recorded semantic result; reusing a key for a different payload conflicts. Idempotency records are keyed by assignment, operation, and key; raw keys/request bodies are not intended as telemetry labels.

An exclusive active-reservation index prevents two transfers from owning one bed at once. JPA transaction boundaries, optimistic versions, locking, database constraints, and focused PostgreSQL tests protect the tested races. This is synchronous, single-database coordination: there is no distributed transaction, message broker, or outbox claim.

## Operational summaries and audit

Capacity/dashboard queries derive the branch/hospital sets from server authority and aggregate operational counts. They are not clinical decision support and do not imply forecasting or throughput guarantees. Audit events retain acting context and bounded hospital/transfer identifiers; denied or conflicted operations are not successful mutations and must not emit success events. Correlation context is bounded. Patient names, contact values, request bodies, credentials, and tokens do not belong in audit details or telemetry.

## Schema and implementation references

- Flyway: `backend/src/main/resources/db/migration/V5__hospital_network_hierarchy.sql`, `V6__network_patient_identity.sql`, and `V7__transfer_reservation_idempotency.sql`.
- Authority/hierarchy: `organization/NetworkHierarchyService`, `auth/ActingContextService`, `auth/BranchAccessService`, and `auth/JwtFilter`.
- Patient access: `patient/PatientService`, `PatientHospitalAccess`, and `PatientHospitalAccessRepository`.
- Transfer: `transfer/TransferController`, `TransferAuthorizationService`, and `TransferService`; idempotency under `idempotency/`.
- Focused evidence code: `Phase5MigrationIntegrationTest`, `TransferV7IntegrityIntegrationTest`, `TransferConcurrencyIntegrationTest`, and the Phase 5 Playwright specs. Exact overall acceptance remains PARTIAL; see [`../implementation-status.md`](../implementation-status.md) and [`../runbook.md`](../runbook.md).
