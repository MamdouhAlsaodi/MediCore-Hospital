# Phase 5 Threat Model — Synthetic Hospital Network

> **Scope and limit:** This is a focused design threat model for the Phase 5 hierarchy, patient-access grants, transfer races, and idempotency boundaries. It is not a security certification, production penetration test, compliance assessment, or complete threat model of MediCore. All review fixtures are synthetic.

## Assets and security boundaries

- **Authority state:** account, enabled assignment, role, organization/network, hospital, branch, department, and ancestor lifecycle state.
- **Patient identity and access:** the network-level synthetic patient record and each hospital's explicit `PatientHospitalAccess` grant.
- **Transfer integrity:** source/destination ownership, lifecycle state, destination bed reservation, admission effects, and immutable initiating context.
- **Command replay state:** assignment/operation-scoped idempotency key, request fingerprint, and prior result.
- **Evidence:** audit actor/assignment/scope/hospital/transfer context and bounded correlation identifier.
- **Trust boundary:** browser/client input and JWT-carried structural identifiers are untrusted references. Server-reloaded state and database constraints determine authorization and integrity. The phase targets a single application and PostgreSQL database, not a hostile or distributed infrastructure model.

## Threats, controls, and residual risk

| Threat / abuse case | Current control and expected behavior | Residual risk / verification boundary |
|---|---|---|
| Tampered or stale organization/hospital/branch/department claims, changed assignment, or disabled ancestor | JWT filter and acting-context services re-resolve current server state and validate the full active ancestor chain and structural claim match. Context switching selects only subject-owned assignments and valid descendants. Denials do not mutate data. | Tests establish application behavior on the tested fixtures; they do not prove resistance to a compromised signing secret, host, or database. No MFA/SSO claim. |
| Cross-hospital identifier probing or hierarchy enumeration | Repository/service reads are constrained by acting hospital and authorized hierarchy; patient and transfer reads require applicable authority/grants and use generic refusal/not-found behavior. Client-supplied IDs do not widen scope. | Side channels, deployment-layer logging, and every legacy/unconverted route require separate review; the network is still a synthetic single-network boundary. |
| Unauthorized patient visibility after migration or before transfer | V6 derives one legacy access grant from verified branch ownership and aborts on unknown ownership. Patient operations join through an active hospital grant. Transfer acceptance adds destination access in the same transaction as transfer acceptance/reservation. | Correctness depends on migration/test fixtures and current DB state. This does not authorize broad network patient browsing or real-data use. |
| Two transfers accept the same destination bed, or stale lifecycle commands race | Transfer transitions are transactional/versioned; active reservation has a database uniqueness guard per bed; invalid/conflicting transitions return conflict without partial success. Testcontainers-backed integration tests cover selected PostgreSQL races. | Only the exercised local PostgreSQL/Testcontainers patterns are evidenced. No distributed lock, multi-region, or production load guarantee. |
| Retry duplicates a command or an idempotency key is reused with changed content | Mutating transfer calls require a bounded `Idempotency-Key`; key identity is scoped to assignment and operation; request fingerprint distinguishes changed payload; identical replay returns prior result and changed-payload reuse conflicts. | Storage retention/cleanup and unbounded operational volume are outside this phase's acceptance claim. Keys/fingerprints must not become metric labels or logs. |
| Cancellation/rejection releases a bed owned by another workflow, or completion leaves inconsistent admission/bed state | Reservation row is explicitly transfer-owned; legal transition effects are in one transaction. Completion consumes reservation while binding/creating destination admission and occupying the destination bed; rollback prevents partial commits. | Transaction guarantees apply to the same database transaction and tested constraints; not to external downstream systems. |
| Audit falsely records a successful denied/conflicted mutation or leaks patient details | Successful commands carry bounded acting/hospital/transfer context; denied/conflicted commands must create no success audit. Audit fields avoid patient names/contact/request bodies; correlation and identifiers are bounded. | Audit retention, export access, host logs, and deployment SIEM are not fully governed here; no compliance claim. |
| Browser displays data from a prior acting context after switch | UI data is keyed/remounted by acting context; browser tests include context discrimination and journey assertions. This is a defense against stale rendering, not a server authorization mechanism. | A browser test suite cannot replace exhaustive route authorization or end-user device security. |
| Invalid legacy data is silently assigned to a hospital or grant | V5/V6 migrations are forward-only and guard invalid ownership/shape/collisions; ambiguous ownership blocks rather than guessing. | The proof is bounded to synthetic empty and Phase 4-shaped fixtures. Arbitrary customer databases are not a supported target. |

## Security invariants for review

1. Never authorize from client-provided organization, hospital, branch, department, patient, or transfer IDs alone.
2. Recompute assignment and active hierarchy authority from current server state on each request; keep one acting role rather than unioning roles.
3. Require an active hospital access grant for hospital-scoped patient operations; a transfer creates destination access only as part of successful acceptance.
4. Commit the transfer state, bed reservation/release/consumption, patient grant, admission effects, idempotency outcome, and success audit consistently within the transaction specified for each command. Conflict/refusal leaves no partial success state or success audit.
5. Keep idempotency keys bounded; never emit tokens, passwords, request bodies, patient names/contact values, or raw keys as logs/metrics.
6. Fail closed on unresolved migration ownership, malformed hierarchy, inactive ancestors, foreign identifiers, and conflicts.

## Evidence and gaps

Relevant implementation includes `auth/ActingContextService`, `auth/JwtFilter`, `organization/NetworkHierarchyService`, `patient/PatientService`, `transfer/TransferAuthorizationService`, `transfer/TransferService`, `idempotency/IdempotencyService`, and Flyway V5–V7. Related verification code includes `Phase5MigrationIntegrationTest`, `TransferAuthorizationMatrixTest`, `TransferWorkflowApiTest`, `TransferV7IntegrityIntegrationTest`, `TransferConcurrencyIntegrationTest`, and Phase 5 browser journeys.

Reported suite results are backend 392/392 (including PostgreSQL Testcontainers), frontend 343/343, browser 7 passed with 3 intentionally skipped mobile duplicates, and OpenAPI drift clean. Those focused results do not mean the Phase 5 acceptance gate has passed: `scripts/phase5/acceptance.sh` and `scripts/phase5/test-transfer-concurrency.sh` are present, but the acceptance script is not executable as checked out and no full run/final result is recorded here. The full FR/SC evidence map is still incomplete. The phase therefore remains **PARTIAL**; see [`../implementation-status.md`](../implementation-status.md).
