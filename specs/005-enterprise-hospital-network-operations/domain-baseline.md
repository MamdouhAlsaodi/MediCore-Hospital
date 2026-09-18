# Phase 4 Domain Baseline (Phase 5 T008 inventory)

**Status:** Source-grounded inventory only — read from this worktree at baseline
`0c5a303a72b31e15cdac0d41773580a3585605e1`. No behavior change is proposed here.
It records exactly the patient/admission/bed/dashboard/audit dependencies Phase 5
must preserve or narrowly extend.

## Patient domain (`patient` package)

- `Patient` entity (`patients` table): `medicalRecordNumber` (globally unique in the one
  network), `fullName` (required), optional PII (`dateOfBirth`, `sex`, `phone`, `email`,
  `nationalId`, `address` ≤1000), `active=true` default, **`@ManyToOne branch`
  (`patients.branch_id`) — branch-owned today; Phase 5 T065 moves authority to the
  organization/network with explicit `PatientHospitalAccess` per hospital (T066).**
- `PatientRepository`: `findByMedicalRecordNumber`, `findByFullNameContainingIgnoreCase`,
  branch-scoped reads `findByIdAndBranchId`, `findByBranchId`,
  `findByBranchIdAndFullNameContainingIgnoreCase`; grouped metric
  `countByBranchIdInGrouped` → `BranchMetric(branchId, total)` for the dashboard.
  A cross-branch id resolves to empty → 404, indistinguishable from nonexistent
  (non-enumeration). **Phase 5 T068 replaces branch filters with network identity +
  hospital-access joins.**
- `PatientService` dependencies: `PatientRepository`, `BranchRepository`, `AuditService`.
  `create` pre-checks global MRN uniqueness (`DuplicateKeyException`), derives branch from the
  acting context (never client input), audits `CREATE`; `get/list/update` are branch-scoped via
  `actingBranchId()`; `update` audits `UPDATE`. Acting branch is resolved through the
  `ActingContext` principal and fails closed (`AccessDeniedException`) when missing.
- `PatientController`/`PatientDtos` parse/map only. `POST /api/patients` and
  `PUT /api/patients/*` require elevated roles (`SecurityConfig`).

## Admission domain (`admission` package)

- `Admission` (`admissions`): `patientId` (string reference), `admittedAt`/`dischargedAt`
  (timestamptz after V3, converted through the owning branch zone), `reason`, `status`
  (`ADMITTED → DISCHARGED` only), `branchId`. Lifecycle is server-owned
  (`InvalidStateTransitionException` otherwise).
- `AdmissionService` dependencies: `AdmissionRepository`, `PatientRepository`, `BedRepository`,
  `AdmissionBedAssignmentRepository`, `BranchRepository`, `AuditService` — the densest
  cross-domain seam; **Phase 5 T073 must add hospital-access validation to this lookup path
  without changing its transactional shape.**
- `AdmissionBedAssignment` (`admission_bed_assignments`): unique active-per-admission
  (`uq_assignment_active_admission`) and unique active-per-bed (`uq_assignment_active_bed`) —
  the invariant a Phase 5 `TransferBedReservation` must coordinate with (never bypass).
- `AdmissionController`/`AdmissionDtos`: create, status transition, bed assign, read.

## Bed domain (`bed` package)

- `Bed` entity (`beds`): `ward`, `room`, `bedNumber`, `occupancyStatus`
  (`AVAILABLE|MAINTENANCE|OUT_OF_SERVICE|OCCUPIED` check), `patientId` string, `branch`
  ownership; unique `(branch_id, ward, room, bed_number)`.
- `BedRepository`: branch-scoped `findByIdAndBranchId`, `findByBranchId`,
  `findByBranchIdAndWardAndRoomAndBedNumber`; grouped `countByBranchIdInGroupedByStatus` →
  `BranchStatusCount(branchId, status, total)` for capacity summaries. **Phase 5 T107 makes
  these queries hospital-aware for the capacity endpoints.**

## Dashboard/reporting domain (`reporting` package)

- `DashboardService` dependencies: `PatientRepository`, `AppointmentRepository`,
  `AdmissionRepository`, `EmergencyVisitRepository`, `InvoiceRepository`, `BedRepository`,
  `BranchRepository`, `HospitalOrganizationRepository`, plus a `dashboardClock` bean
  (overridable in tests; JVM-default zone, no invented zone policy).
- Endpoints (`DashboardController`, all authenticated): `GET /api/dashboard/branch` —
  `BranchSummary` for the acting branch resolved from the acting context (denies otherwise);
  `GET /api/dashboard/network` — `NetworkSummary` for enabled `ORGANIZATION`-scoped ADMIN
  contexts only: every active branch in deterministic code order, all-zero summaries included,
  totals = exact sum over branches; `GET /api/dashboard` — legacy map summary. Reads record no
  audit events. Grouped queries (`countByBranchIdInGrouped`,
  `countByBranchIdInGroupedByStatus`) keep the reads bounded — the query-count discipline
  Phase 5 T121/T037 pins. **Phase 5 T118–T120 extracts hospital/network aggregation into
  `NetworkOperationsDashboardService` while preserving branch compatibility.**

## Audit domain (`audit` package)

- `AuditEvent` (`audit_events`): `actor`, `action` (required), `resourceType`, `resourceId`,
  `details` ≤2000, `occurredAt`, `correlationId` ≤64, `assignmentId`, `role` + `scope`
  (check-domain enums), `organizationId`, `branchId`, `departmentId`. **No hospital column at
  baseline — Phase 5 T026 adds acting-hospital and bounded transfer-context columns.**
- `AuditService.record(...)` derives context from the `ActingContext` principal; writes happen
  only inside successful service transactions (denied/conflicted commands record no success
  audit). `AuditController` is ADMIN-only (`/api/audit/**`) with allowlisted read DTOs — no
  patient payloads. Evidence indexes: `(organization_id|branch_id|department_id, occurred_at)`.

## Synthetic fixture (`bootstrap/DemoDataInitializer`)

- One clearly synthetic organization `DEMO-ORG-001` with three branches: `DEMO-BR-001`
  (default/main, zone UTC), `DEMO-BR-002` (north, `America/New_York`), `DEMO-BR-003`
  (harbor, `Asia/Tokyo`); branch-owned departments, professionals, patients
  (`DEMO-PAT-*`), beds, appointments, admissions, emergency visits, invoices
  (`DEMO-INV-01xx/02xx/03xx`). Seed variance is bounded by `MEDICORE_DEMO_SEED`.
- Every audit event mirrors the organization/branch of the resource it describes; rows without
  an owner keep the legacy null seam. **Phase 5 T027 adds exactly one legacy hospital beneath
  the organization and backfills the existing branches/patients deterministically; T038 expands
  to ≥3 hospitals / ≥2 branches each / ≥2 zones.**

## Cross-domain conclusions Phase 5 must respect

1. Patient identity is created/read/updated branch-scoped today; every dependent workflow
   (appointments, admissions, emergency, invoices, clinical families) resolves patients by
   branch-scoped service paths — all of them need the same hospital-access gate (T073/T074).
2. Bed occupancy `OCCUPIED` is admission-owned; a transfer reservation must compose with the
   `uq_assignment_active_*` invariants, never replace them.
3. Dashboard totals are exact sums over explicitly derived branch sets — hospital/network
   aggregation must extend, not approximate, this rule, with bounded queries.
4. Audit writes ride successful transactions only; new hospital/transfer context columns stay
   bounded and allowlisted on read.
