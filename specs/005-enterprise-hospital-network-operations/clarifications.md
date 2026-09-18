# Phase 5 Clarifications and Locked Planning Decisions

**Status:** Planning decisions only. None authorizes implementation.

## Specify → Clarify result

No unresolved ambiguity remains that blocks a detailed implementation plan. The following decisions are locked for this draft because they follow the owner-approved direction and minimize unnecessary change. Any reversal requires updating `spec.md`, `research.md`, `data-model.md`, contracts, tasks, and traceability before implementation.

| Topic | Decision | Reason |
|---|---|---|
| Enterprise boundary | One `HospitalOrganization` remains the network boundary | Preserves accepted authorization and avoids accidental SaaS tenancy |
| Hospital hierarchy | Add `HospitalFacility` between organization and branch | Expresses network → hospital → branch → department with minimal migration |
| Existing scope name | Keep wire value `ORGANIZATION` as network scope; add `HOSPITAL` | Avoids breaking existing clients/tokens while adding hospital authority |
| Request context | Every authorized request remains bound to one concrete branch and now one concrete hospital | Prevents ambiguous broad authority during operational requests |
| Patient identity | One network-level `Patient`; hospital visibility through explicit `PatientHospitalAccess` | Prevents duplicates without making patient records globally visible |
| Existing MRN rule | Keep globally unique MRN inside the one network | Matches Phase 4 FR-014; no unproven scope migration |
| Transfer architecture | Synchronous transactional modular-monolith workflow | One database can enforce atomic lifecycle and reservation; no broker needed |
| Transfer state machine | `REQUESTED → ACCEPTED → IN_TRANSIT → COMPLETED`; `REJECTED` and `CANCELLED` are terminal alternatives | Smallest lifecycle that proves ownership handoff and resource coordination |
| Bed reservation | Dedicated `TransferBedReservation`, not a new generic allocation engine | Avoids overengineering and makes ownership/release explicit |
| Idempotency | Persist bounded command keys and request fingerprint for transfer mutations | Prevents duplicate commands without introducing distributed infrastructure |
| Command center | Operational counts only; no clinical risk or recommendation metrics | Preserves non-clinical portfolio boundary |
| API evolution | Extend current v1 contract compatibly; no v2 unless compatibility tests prove a required break | Minimizes client churn |
| Runtime architecture | Preserve Spring Boot modular monolith + React SPA + PostgreSQL | Phase 5 complexity is domain/isolation, not service topology |
| Verification | TDD plus disposable PostgreSQL, OpenAPI drift, desktop/mobile E2E, canonical acceptance | Matches project constitution and prior accepted evidence standard |
| Publication | No commit, push, PR, merge, release, or deploy in this planning action | Owner explicitly withheld execution authorization |

## Governance conflict identified

The current `.specify/memory/constitution.md` is explicitly scoped to Phase 4 and says one synthetic organization with three branches remains the boundary. Before Phase 5 implementation, a **Phase 5 amendment** must be approved that:

1. Retains the synthetic-only Training/Portfolio boundary.
2. Retains server-owned authority, PostgreSQL-real verification, forward-only migrations, observability, contract-first changes, and small evidence-bearing steps.
3. Replaces the Phase 4 hierarchy constraint with one synthetic network containing multiple synthetic hospitals and branches.
4. Does not reinterpret this hierarchy as SaaS customer tenancy.
5. Updates workflow/gate references from Phase 4 to Phase 5 without weakening any accepted gate.

The constitution is not modified by this plan-only action.

## Decisions deliberately deferred

These do not block the Training/Portfolio phase and MUST NOT be invented during implementation:

- Legal retention periods and regulatory obligations.
- Real hospital operating policies or clinical transfer protocols.
- Production capacity/latency targets, SLA/SLO, RPO/RTO.
- External interoperability standards or integrations.
- Commercial tenant provisioning, billing, pricing, and support model.
