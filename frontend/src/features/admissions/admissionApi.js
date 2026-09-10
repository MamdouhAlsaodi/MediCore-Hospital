import { apiFetch } from '../../api.js';

// The only admission transport adapter (docs/plan2.md Task 2). Bodies mirror
// AdmissionDtos exactly:
//   - list:     GET /api/admissions -> AdmissionResponse[]
//               (id, patientId, admittedAt, dischargedAt, reason, status)
//   - register: POST /api/admissions with CreateAdmissionRequest
//               { patientId: UUID, admittedAt: ISO LocalDateTime, reason }
//               — the server sets status=ADMITTED and owns discharge, so the
//               adapter deliberately sends exactly the three contract fields.
//   - discharge: PUT /api/admissions/{id}/status with
//               { status: 'DISCHARGED' } — legal only from ADMITTED; a
//               repeat or any other transition is refused with a safe 409.
// patientId is a typed UUID reference validated server-side; unknown
// references are rejected with 404 and malformed bodies with 400 by the
// shared GlobalExceptionHandler. All transport goes through the shared
// apiFetch (Bearer token, JSON, ApiError, onUnauthorized); components never
// call fetch directly.
export function fetchAdmissions({ token, onUnauthorized } = {}) {
  return apiFetch('/api/admissions', { method: 'GET', token, onUnauthorized });
}

export function createAdmission({ token, admission, onUnauthorized } = {}) {
  return apiFetch('/api/admissions', {
    method: 'POST',
    token,
    body: {
      patientId: admission?.patientId,
      admittedAt: admission?.admittedAt,
      reason: admission?.reason,
    },
    onUnauthorized,
  });
}

export function dischargeAdmission({ token, id, onUnauthorized } = {}) {
  const safeId = encodeURIComponent(String(id ?? ''));
  return apiFetch(`/api/admissions/${safeId}/status`, {
    method: 'PUT',
    token,
    body: { status: 'DISCHARGED' },
    onUnauthorized,
  });
}
