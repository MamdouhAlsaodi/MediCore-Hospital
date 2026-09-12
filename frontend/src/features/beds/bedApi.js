import { apiFetch } from '../../api.js';

// The only bed transport adapter (docs/plan3.md Task 6). Bodies mirror
// BedDtos exactly:
//   - list:       GET /api/beds -> BedResponse[]
//                 (id, branchId, ward, room, bedNumber, occupancyStatus)
//                 — branch-scoped to the acting context, no persistence
//                 metadata and no legacy patient reference anywhere.
//   - create:     POST /api/beds with exactly CreateBedRequest
//                 { ward, room, bedNumber } — the server derives the owning
//                 branch from the acting context and sets the initial
//                 AVAILABLE status itself, so the adapter deliberately
//                 sends exactly the three contract fields and refuses to
//                 send any branch, status, or patient input.
//   - transition: PUT /api/beds/{id}/status with { status } — legal only
//                 between AVAILABLE, MAINTENANCE, and OUT_OF_SERVICE; a
//                 request for OCCUPIED (admission-owned), an unknown
//                 target, or an illegal move is refused with a safe 409.
// Unknown bed ids and cross-branch ids are rejected with 404 and malformed
// bodies with 400 by the shared GlobalExceptionHandler. All transport goes
// through the shared apiFetch (Bearer token, JSON, ApiError,
// onUnauthorized); components never call fetch directly.
export function fetchBeds({ token, onUnauthorized } = {}) {
  return apiFetch('/api/beds', { method: 'GET', token, onUnauthorized });
}

export function createBed({ token, bed, onUnauthorized } = {}) {
  return apiFetch('/api/beds', {
    method: 'POST',
    token,
    body: {
      ward: bed?.ward,
      room: bed?.room,
      bedNumber: bed?.bedNumber,
    },
    onUnauthorized,
  });
}

export function transitionBed({ token, id, status, onUnauthorized } = {}) {
  const safeId = encodeURIComponent(String(id ?? ''));
  return apiFetch(`/api/beds/${safeId}/status`, {
    method: 'PUT',
    token,
    body: { status },
    onUnauthorized,
  });
}
