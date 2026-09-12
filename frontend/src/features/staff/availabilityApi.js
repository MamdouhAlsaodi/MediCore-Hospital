import { apiFetch } from '../../api.js';

// The availability transport adapter for the Task 9 scheduling flow
// (docs/plan3.md Task 9). Reads the allowlisted interval DTO
//   - window: GET /api/staff/{id}/availability?from=&to=
//             -> AvailabilityResponse[]
//             (id, branchId, staffMemberId, startsAt, endsAt)
// for one professional inside the required half-open [from, to) window.
// Both bounds are ISO LocalDateTime strings in the server's UTC/ISO
// contract, passed through exactly as provided — this adapter never
// converts, localizes, or invents a branch time zone. The bearer token
// from the acting-context session is the only client credential/scope
// input; branch scoping rides the token alone, never a client-sent
// branch value. All transport goes through the shared apiFetch (JSON,
// ApiError, onUnauthorized); components never call fetch directly.
export function fetchStaffAvailability({ token, staffId, from, to, onUnauthorized } = {}) {
  const window = new URLSearchParams({ from, to });
  return apiFetch(`/api/staff/${encodeURIComponent(staffId)}/availability?${window}`, {
    method: 'GET',
    token,
    onUnauthorized,
  });
}

// Renders one modeled interval as its honest ISO span. Times are displayed
// exactly as the server models them; this is schedule geometry only — it
// never suggests clinical appropriateness of any time.
export function availabilityIntervalText(interval) {
  const startsAt = typeof interval?.startsAt === 'string' ? interval.startsAt : '';
  const endsAt = typeof interval?.endsAt === 'string' ? interval.endsAt : '';
  if (!startsAt || !endsAt) return '';
  return `${startsAt} – ${endsAt}`;
}
