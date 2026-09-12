import { apiFetch } from '../../api.js';

// The only emergency-visit transport adapter (docs/plan2.md Task 3). Bodies
// mirror EmergencyVisitDtos exactly:
//   - list:       GET /api/emergency-visits -> EmergencyVisitResponse[]
//                 (id, branchId, patientId, arrivalAt, triageLevel,
//                 chiefComplaint, status)
//   - register:   POST /api/emergency-visits with CreateEmergencyVisitRequest
//                 { patientId: UUID, arrivalAt: ISO LocalDateTime,
//                 triageLevel: '1'..'5', chiefComplaint } — the server sets
//                 status=WAITING and owns the lifecycle, so the adapter
//                 deliberately sends exactly the four contract fields.
//   - transition: PUT /api/emergency-visits/{id}/status with { status } —
//                 legal only along WAITING -> IN_TREATMENT | CLOSED and
//                 IN_TREATMENT -> CLOSED; a repeat, backward move, or
//                 unknown target is refused with a safe 409.
// patientId is a typed UUID reference validated server-side inside the
// acting branch (docs/plan3.md Task 8): unknown and cross-branch references
// are rejected with 404 and malformed bodies with 400 by the shared
// GlobalExceptionHandler. Every list/detail/transition resolves only inside
// the branch bound to the context-bound token — scope is always
// server-derived from the token, never a client-selected value, so the
// adapters carry no branch input of any kind. branchId on each row is the
// server-stamped owning branch. triageLevel is a NEUTRAL demo label with no
// clinical meaning — not a triage protocol of any kind. All transport goes
// through the shared apiFetch (Bearer token, JSON, ApiError, onUnauthorized);
// components never call fetch directly.
export function fetchEmergencyVisits({ token, onUnauthorized } = {}) {
  return apiFetch('/api/emergency-visits', { method: 'GET', token, onUnauthorized });
}

export function createEmergencyVisit({ token, visit, onUnauthorized } = {}) {
  return apiFetch('/api/emergency-visits', {
    method: 'POST',
    token,
    body: {
      patientId: visit?.patientId,
      arrivalAt: visit?.arrivalAt,
      triageLevel: visit?.triageLevel,
      chiefComplaint: visit?.chiefComplaint,
    },
    onUnauthorized,
  });
}

export function transitionEmergencyVisit({ token, id, status, onUnauthorized } = {}) {
  const safeId = encodeURIComponent(String(id ?? ''));
  return apiFetch(`/api/emergency-visits/${safeId}/status`, {
    method: 'PUT',
    token,
    body: { status },
    onUnauthorized,
  });
}
