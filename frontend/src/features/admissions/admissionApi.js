import { apiFetch } from '../../api.js';
// Phase 4 (plan Task 10, T073; FR-017): request shapes come from the
// generated typed contract; the conditional bedId-inclusion rule and every
// wire byte stay exactly as pinned by the admission screen tests.
import {
  assignAdmissionBed as assignAdmissionBedHttpRequest,
  createAdmission as createAdmissionHttpRequest,
  dischargeAdmission as dischargeAdmissionHttpRequest,
  listAdmissions as listAdmissionsHttpRequest,
} from '../../generated/api/index';

// The only admission transport adapter (docs/plan2.md Task 2, extended by
// docs/plan3.md Task 7). Bodies mirror AdmissionDtos exactly:
//   - list:     GET /api/admissions -> AdmissionResponse[]
//               (id, branchId, patientId, admittedAt, dischargedAt, reason,
//               status, currentBed) — currentBed is null or the allowlisted
//               { bedId, ward, room, bedNumber } summary of the held bed.
//   - register: POST /api/admissions with CreateAdmissionRequest
//               { patientId: UUID, admittedAt: ISO LocalDateTime, reason,
//               bedId?: UUID } — the server sets status=ADMITTED and owns
//               discharge, so the adapter sends exactly the three contract
//               fields and adds bedId ONLY when a bed was selected; with no
//               selection the body stays the exact three-field contract.
//   - assign:   PUT /api/admissions/{id}/bed with { bedId } — the same
//               narrow command performs the initial assignment and every
//               later atomic transfer; the server refuses an unavailable,
//               unknown, or repeated target with the safe 409.
//   - discharge: PUT /api/admissions/{id}/status with
//               { status: 'DISCHARGED' } — legal only from ADMITTED; the
//               server releases a held bed atomically on this transition,
//               and a repeat or any other move is refused with a safe 409.
// patientId and bedId are typed UUID references validated server-side;
// unknown or cross-branch references are rejected with 404 and malformed
// bodies with 400 by the shared GlobalExceptionHandler. All transport goes
// through the shared apiFetch (Bearer token, JSON, ApiError,
// onUnauthorized); components never call fetch directly.
export function fetchAdmissions({ token, onUnauthorized } = {}) {
  const request = listAdmissionsHttpRequest();
  return apiFetch(request.path, { method: request.method, token, onUnauthorized });
}

export function createAdmission({ token, admission, onUnauthorized } = {}) {
  // bedId is part of the body only when a bed was actually selected — the
  // no-bed create keeps the exact three-field contract of Task 2.
  const body = {
    patientId: admission?.patientId,
    admittedAt: admission?.admittedAt,
    reason: admission?.reason,
  };
  if (admission?.bedId) body.bedId = admission.bedId;
  const request = createAdmissionHttpRequest(body);
  return apiFetch(request.path, {
    method: request.method,
    token,
    body: request.body,
    onUnauthorized,
  });
}

export function assignAdmissionBed({ token, id, bedId, onUnauthorized } = {}) {
  const request = assignAdmissionBedHttpRequest(id, { bedId });
  return apiFetch(request.path, {
    method: request.method,
    token,
    body: request.body,
    onUnauthorized,
  });
}

export function dischargeAdmission({ token, id, onUnauthorized } = {}) {
  const request = dischargeAdmissionHttpRequest(id, { status: 'DISCHARGED' });
  return apiFetch(request.path, {
    method: request.method,
    token,
    body: request.body,
    onUnauthorized,
  });
}
