import { apiFetch } from '../../api.js';

// The only audit transport adapter (plan1.md Task 10). Strictly read-only:
//
//   list: GET /api/audit -> AuditEvent[] as persisted by AuditService
//         (id, actor, action, resourceType, resourceId, details, occurredAt,
//          plus BaseEntity id/createdAt/updatedAt/version)
//
// /api/audit/** is ADMIN-only in backend SecurityConfig ("hasRole(\"ADMIN\")");
// the adapter adds no client-side authorization of its own and the screen
// consuming it displays only the evidence fields, never tokens, credentials,
// or request bodies. The adapter sends exactly the shared apiFetch envelope
// (Bearer token, ApiError, onUnauthorized) and never a body — components
// never call fetch directly.
export function fetchAuditEvents({ token, onUnauthorized } = {}) {
  return apiFetch('/api/audit', { method: 'GET', token, onUnauthorized });
}
