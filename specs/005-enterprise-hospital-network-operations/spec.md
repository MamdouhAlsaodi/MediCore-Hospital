# Feature Specification: Phase 5 — Enterprise Hospital Network Operations

**Feature Branch**: `phase5/enterprise-hospital-network-operations` *(planned only; branch not created)*

**Created**: 2026-09-16

**Status**: Draft plan — owner has not authorized implementation

**Input**: Evolve the accepted synthetic Training/Portfolio MediCore system from one hospital organization with branches into one enterprise hospital network containing multiple hospitals, branches, and departments, with server-owned hierarchical authority, network patient identity, safe inter-hospital transfer, capacity coordination, and a command center. Keep all data synthetic and make no clinical, production, regulatory, capacity, or SLA claim.

## User Scenarios & Testing

### User Story 1 — Govern a multi-hospital hierarchy (Priority: P1)

A network administrator can inspect one hospital network containing multiple hospitals, each with its own branches and departments, while hospital and branch users see only their authorized hierarchy.

**Why this priority**: Every later workflow requires an unambiguous, server-owned network → hospital → branch → department hierarchy.

**Independent Test**: Load a synthetic network with at least three hospitals across at least two IANA time zones, authenticate at network/hospital/branch scopes, and prove each hierarchy response contains exactly the authorized descendants.

**Acceptance Scenarios**:
1. **Given** a network-scoped administrator, **When** the hierarchy is requested, **Then** every active synthetic hospital and its active branches appear in deterministic order.
2. **Given** a hospital-scoped administrator, **When** the same hierarchy is requested, **Then** only that hospital and its branches appear.
3. **Given** a branch-scoped user, **When** identifiers from another hospital are supplied, **Then** the request is refused without disclosing whether those identifiers exist.

---

### User Story 2 — Switch hierarchical acting context safely (Priority: P1)

A user with multiple assignments can switch between permitted hospitals and branches; authority is re-derived from server state on every request, and no previous-context data is rendered during the switch.

**Why this priority**: Hierarchical identity and isolation are the security foundation for all network operations.

**Independent Test**: Switch among network, hospital, branch, and department assignments; tamper with every structural claim and target identifier; verify authorized switches succeed, unauthorized switches fail generically, and stale data never paints.

**Acceptance Scenarios**:
1. **Given** a valid network assignment, **When** the user selects an allowed hospital and branch, **Then** the replacement session binds all three IDs and one role.
2. **Given** a disabled hospital, branch, or assignment, **When** an existing token is reused, **Then** the request becomes unauthenticated or refused immediately.
3. **Given** an in-flight response from the previous context, **When** context changes, **Then** that response cannot render in the new context.

---

### User Story 3 — Use one patient identity across authorized hospitals (Priority: P1)

Authorized staff can resolve one synthetic patient identity at network level without creating duplicate patient records, while each hospital sees the identity only through an explicit active hospital access grant.

**Why this priority**: Transfers and referrals cannot be reliable if each hospital creates an unrelated patient duplicate.

**Independent Test**: Create a patient in Hospital A, prove Hospital B cannot search or fetch it, accept a transfer that grants Hospital B access, and prove both hospitals now resolve the same patient ID while Hospital C remains unable to discover it.

**Acceptance Scenarios**:
1. **Given** an acting hospital with no access grant, **When** it searches another hospital’s patient identifier, **Then** the result is empty or generic not-found.
2. **Given** an accepted transfer, **When** the destination hospital resolves the patient, **Then** it receives the same network patient ID and no duplicate row is created.
3. **Given** concurrent attempts to register the same network MRN, **When** both commit, **Then** exactly one patient identity exists and the loser receives a stable conflict response.

---

### User Story 4 — Coordinate an inter-hospital transfer safely (Priority: P1)

A source hospital can request a synthetic patient transfer, the destination can accept or reject it, an available destination bed can be reserved atomically, and each lifecycle transition has an owner, timestamp, and audit record.

**Why this priority**: This is the first complete enterprise-network workflow and the central Phase 5 portfolio demonstration.

**Independent Test**: Run REQUESTED → ACCEPTED → IN_TRANSIT → COMPLETED across two hospitals and separately exercise REJECTED and CANCELLED paths, including concurrent acceptance for one bed and repeated idempotent commands.

**Acceptance Scenarios**:
1. **Given** a source user and active admission, **When** a transfer request targets another active hospital, **Then** the destination can view the request but unrelated hospitals cannot discover it.
2. **Given** two acceptance attempts for one available bed, **When** they race, **Then** exactly one reservation succeeds, the other returns `409`, and no partial transition or false success audit is stored.
3. **Given** an accepted transfer, **When** it is cancelled before transit, **Then** the reservation is released atomically.
4. **Given** an in-transit transfer, **When** completion succeeds, **Then** the destination admission uses the same patient identity and the reserved bed becomes occupied in one transaction.

---

### User Story 5 — Coordinate facility-aware capacity and scheduling (Priority: P2)

Authorized users can inspect bed capacity and appointment availability at their permitted hospital/branch scope without double-booking resources or crossing facility boundaries.

**Why this priority**: Enterprise operations require trustworthy capacity information, but it depends on hierarchy, identity, and transfer foundations.

**Independent Test**: Query capacity at branch, hospital, and network scopes; race bed reservations and overlapping appointments; verify exact aggregation and one legal winner.

**Acceptance Scenarios**:
1. **Given** a hospital-scoped user, **When** capacity is requested, **Then** totals equal the sum of its active branches only.
2. **Given** an unauthorized hospital ID, **When** capacity is requested, **Then** the system refuses without returning counts.
3. **Given** concurrent reservation or scheduling attempts, **When** they conflict, **Then** exactly one legal winner persists.

---

### User Story 6 — Operate a network command center (Priority: P2)

A network administrator can view synthetic operational summaries by hospital and drill down to branch summaries, while hospital administrators see only their hospital.

**Why this priority**: It demonstrates enterprise governance and operational visibility without introducing clinical decision support.

**Independent Test**: Seed three hospitals with distinct synthetic metrics, compare API totals to repository invariants, and verify UI drill-down at desktop and mobile viewports.

**Acceptance Scenarios**:
1. **Given** a network administrator, **When** the command center loads, **Then** network totals equal the sum of returned hospital totals and each hospital total equals its branch totals.
2. **Given** a hospital administrator, **When** the command center loads, **Then** no other hospital row, count, label, or identifier appears.
3. **Given** a context switch during loading, **When** the old response resolves, **Then** it is discarded before render.

---

### User Story 7 — Audit and prove isolation across the network (Priority: P2)

A network auditor can trace authorized operations across network, hospital, branch, department, assignment, transfer, and correlation contexts without seeing prohibited payloads or unrelated hospital records.

**Why this priority**: Enterprise claims require evidence that authority and isolation hold in success, denial, concurrency, and recovery paths.

**Independent Test**: Execute allowed and denied hierarchy, patient, transfer, capacity, and context operations; verify successful mutations have one scoped audit row, denied operations have no success row, and audit reads obey scope.

**Acceptance Scenarios**:
1. **Given** a completed transfer, **When** a network auditor inspects its evidence, **Then** every transition identifies actor, assignment, network, source/destination hospitals, relevant branches, action, result, and correlation ID.
2. **Given** a hospital auditor, **When** another hospital’s audit identifier is requested, **Then** it is generically unavailable.
3. **Given** telemetry and audit exports, **When** they are scanned, **Then** no token, password, request body, patient name, contact value, or unbounded identifier label appears.

---

### User Story 8 — Demonstrate the complete network journey (Priority: P3)

A portfolio reviewer can start a disposable PostgreSQL review environment, follow a documented synthetic multi-hospital journey in browser and API, and run one canonical Phase 5 acceptance command.

**Why this priority**: The phase is complete only when its claims are reproducible from clean state and traceable to evidence.

**Independent Test**: From a clean disposable stack, run login → hierarchy/context → patient identity → transfer/reservation → capacity → command center → audit on desktop and mobile, then verify cleanup leaves no Phase 5 resources.

## Edge Cases

- Existing Phase 4 rows have organization and branch ownership but no hospital foreign key.
- A branch points to an inactive/mismatched hospital or a hospital from another organization.
- A network assignment selects a hospital with no active branch.
- A JWT has a valid assignment pointer but tampered hospital/branch/department claims.
- A patient has a legacy branch but no generated hospital access grant.
- Duplicate MRN or transfer request created concurrently.
- Destination hospital/branch/bed becomes inactive after request but before acceptance.
- Two transfers try to reserve the same bed; one request is retried with the same idempotency key and another payload.
- Accepted transfer is cancelled, destination admission fails, or source admission changes before transit.
- Network/hospital totals include inactive facilities, legacy null ownership, or rows outside the authorized set.
- Context changes while hierarchy, command center, patient search, or transfer response is in flight.
- DST boundaries differ between source and destination hospitals.
- Audit row lacks new hospital context during migration or comes from legacy/bootstrap activity.

## Requirements

### Functional Requirements

- **FR-001**: The system MUST model exactly one synthetic hospital network (`HospitalOrganization`) containing multiple `HospitalFacility` records; this phase MUST NOT introduce SaaS customer tenancy.
- **FR-002**: Every active branch MUST belong to exactly one hospital in the same organization; every department MUST inherit hospital/network through its branch.
- **FR-003**: Existing Phase 4 synthetic branches MUST migrate deterministically to one synthetic legacy hospital before `hospital_id` becomes non-null.
- **FR-004**: Hospital and branch lifecycle state MUST be server-owned; inactive ancestors invalidate descendant use and outstanding acting contexts.
- **FR-005**: Assignment scope MUST support existing organization/network scope plus explicit hospital, branch, and department scopes without a role union.
- **FR-006**: Every authenticated request MUST rebuild account, assignment, organization, hospital, branch, and department authority from current server state.
- **FR-007**: Context-switch input MAY identify a target assignment/hospital/branch, but MUST NOT widen beyond server-issued assignments and active descendants.
- **FR-008**: Session and OpenAPI contracts MUST include `hospitalId` and hospital labels where required while preserving one acting role and one concrete branch.
- **FR-009**: Frontend state loaded under one acting-context key MUST never render under another context.
- **FR-010**: Patient identity MUST be unique at network scope and no longer be owned exclusively by one branch.
- **FR-011**: A hospital MUST view a patient only through an explicit active `PatientHospitalAccess` grant belonging to the same network.
- **FR-012**: Migration MUST create one access grant for every valid legacy patient from the hospital owning the patient’s legacy branch; unresolved legacy ownership MUST block migration.
- **FR-013**: Patient create/search/read/update MUST be authorized from server-derived network/hospital context; client hospital/network identifiers MUST NOT grant access.
- **FR-014**: Transfer requests MUST link one network patient, one source hospital/branch, one destination hospital, optional destination branch/bed until acceptance, and an immutable initiating context.
- **FR-015**: Transfer lifecycle MUST be `REQUESTED → ACCEPTED → IN_TRANSIT → COMPLETED`, with terminal alternatives `REJECTED` and `CANCELLED` only from documented states.
- **FR-016**: Only authorized source actors may request/cancel/start transit; only authorized destination actors may accept/reject/complete according to the state matrix.
- **FR-017**: Transfer acceptance MUST atomically validate destination ownership/state, reserve one available bed, create destination patient access, advance state, and record audit evidence.
- **FR-018**: Transfer cancellation/rejection MUST release any owned reservation atomically and MUST NOT release a bed owned by another workflow.
- **FR-019**: Transfer completion MUST atomically create or bind the destination admission, occupy the reserved bed, finalize transfer, and retain one network patient identity.
- **FR-020**: Transfer mutations MUST support bounded idempotency keys; repeated identical commands return the prior result, while key reuse with a different payload returns conflict.
- **FR-021**: PostgreSQL constraints/locking MUST produce exactly one legal winner for bed reservations, patient uniqueness, and conflicting lifecycle transitions.
- **FR-022**: Capacity queries MUST derive branch sets from acting authority and aggregate branch → hospital → network without client-expanded scope.
- **FR-023**: Appointment and bed conflicts MUST remain facility-aware, transactional, and based on exact branch/hospital ownership.
- **FR-024**: Network command-center totals MUST equal the sum of authorized hospital totals; hospital totals MUST equal active authorized branch totals.
- **FR-025**: Command-center metrics MUST remain operational and synthetic; no diagnosis, risk score, medical recommendation, real triage, or clinical decision support is permitted.
- **FR-026**: Audit evidence MUST add `hospital_id` and bounded transfer source/destination context while preserving assignment, role, scope, organization, branch, department, and correlation ID.
- **FR-027**: Denied or conflicted mutations MUST create no success audit event and no partial data.
- **FR-028**: Reads by identifier MUST preserve non-enumeration across hospital boundaries using generic refusal/not-found behavior.
- **FR-029**: OpenAPI and the generated TypeScript client MUST be deterministic and drift-checked for every new/changed Phase 5 contract.
- **FR-030**: PostgreSQL migrations MUST be forward-only, deterministic, restart-idempotent, and verified from empty plus a Phase 4-shaped synthetic baseline.
- **FR-031**: Existing H2 developer tests MAY remain, but hierarchy, migration, uniqueness, transfer concurrency, and reservation claims require disposable PostgreSQL evidence.
- **FR-032**: Browser evidence MUST cover desktop and mobile viewport flows and render-phase context discrimination.
- **FR-033**: Canonical acceptance MUST cover migration, hierarchy, auth/context, patient identity, transfers, capacity, dashboards, audit, OpenAPI drift, frontend, E2E, security, cleanup, and documentation traceability.
- **FR-034**: All fixtures and evidence MUST be synthetic and public-safe; protected runtime env files and real credentials MUST never be read.
- **FR-035**: Planning approval MUST NOT authorize implementation, worker delegation, dependency installation, commit, push, PR, merge, deployment, release, or clinical/production claims.

### Key Entities

- **HospitalOrganization**: Existing aggregate retained as the one synthetic enterprise network and authorization tenant boundary.
- **HospitalFacility**: A hospital inside the network with code, name, region label, IANA time zone, and lifecycle state.
- **Branch**: Operational site belonging to one hospital and inheriting its network.
- **ActingAssignment / ActingContext**: Server-owned identity-to-role-to-scope binding extended with a concrete hospital context.
- **Patient**: Network-level synthetic identity, globally unique by MRN within the one network.
- **PatientHospitalAccess**: Explicit hospital visibility/operability grant for one patient.
- **TransferRequest**: Inter-hospital lifecycle aggregate with source/destination ownership and optimistic version.
- **TransferBedReservation**: Exclusive, stateful reservation of one destination bed for one accepted transfer.
- **IdempotencyRecord**: Bounded command key, request fingerprint, status, and stable prior response reference.
- **AuditEvent**: Immutable evidence extended with hospital and transfer context.

## Success Criteria

- **SC-001**: A clean synthetic dataset contains at least 3 hospitals, at least 2 branches per hospital, and at least 2 IANA time zones, with deterministic hierarchy ordering.
- **SC-002**: An automated authorization matrix proves network, hospital, branch, and department contexts return exactly their allowed hierarchy and zero foreign identifiers/counts.
- **SC-003**: Tampered/stale/disabled assignment and hierarchy claims produce no authorized request and no mutation.
- **SC-004**: Patient migration and runtime tests prove one network patient row, one grant per authorized hospital, and generic invisibility to hospitals without a grant.
- **SC-005**: The complete transfer happy path and rejection/cancellation paths pass against real PostgreSQL with exact state and audit assertions.
- **SC-006**: Repeated real PostgreSQL races for one bed and one transition produce exactly one legal winner, stable conflict losers, and zero partial writes.
- **SC-007**: Repeating the same command with the same idempotency key/payload creates no duplicate; key reuse with a changed payload returns `409`.
- **SC-008**: Every network total equals returned hospital totals and every hospital total equals returned active branch totals for all documented capacity metrics.
- **SC-009**: Desktop and mobile E2E complete the synthetic network journey; stale responses from a prior context never render.
- **SC-010**: OpenAPI/client regeneration is byte-stable and drift-free; representative `200/400/401/403/404/409` contracts pass.
- **SC-011**: Phase 4 accepted workflows remain green or are intentionally migrated with equal/stronger tests and documented compatibility.
- **SC-012**: Public tracked artifacts contain no credentials, private topology, real personal data, unsupported clinical/compliance claims, or runtime dumps.
- **SC-013**: One canonical Phase 5 acceptance command exits `0` only after every required gate passes and leaves zero Phase 5 disposable resources.
- **SC-014**: Traceability maps every FR and SC to exact task(s), tests, runtime evidence, and documentation.

## Assumptions

- The existing `HospitalOrganization` remains the one network boundary; `HospitalFacility` is added beneath it.
- Existing `ORGANIZATION` assignment scope keeps wire compatibility and represents network-level scope; `HOSPITAL` is added.
- The modular monolith and one PostgreSQL database remain; no event broker, microservices split, or distributed transaction is added.
- The accepted Phase 4 global MRN rule remains, interpreted as unique within the one network.
- Existing branches migrate into one deterministic legacy hospital; additional hospitals exist only in synthetic demo fixtures.
- Transfer operations are operational simulations, not medical advice or clinical protocols.
- Implementation, publication, deployment, and release require separate explicit owner authorization.

## Explicitly Out of Scope

- SaaS multi-tenancy or independent customer organizations.
- Real patient/provider data, Pilot operation, or public deployment.
- FHIR, HL7, PACS, insurer, payment, pharmacy dispensing, medical devices, or external identity providers.
- Clinical decision support, diagnosis, treatment, triage protocols, or medical recommendations.
- Regulatory certification, HIPAA/LGPD/GDPR compliance claims, legal retention policy, production capacity, SLA/SLO/RPO/RTO promises.
- Microservices, external event bus, distributed database, multi-region failover, or cross-customer federation.
