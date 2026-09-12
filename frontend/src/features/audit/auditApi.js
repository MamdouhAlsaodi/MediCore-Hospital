import { apiFetch } from '../../api.js';

// The only audit transport adapter (plan1.md Task 10; plan3.md Task 11).
// Strictly read-only:
//
//   list: GET /api/audit?branchId&resourceType&actor&correlationId
//         -> AuditEventView[] as the strict fifteen-field server DTO
//            allowlist (id, actor, action, resourceType, resourceId, details,
//            occurredAt, assignmentId, role, scope, organizationId, branchId,
//            departmentId, correlationId, branchAttribution) — the JPA
//            entity and its persistence metadata are never exposed.
//
// /api/audit/** is ADMIN-only in backend SecurityConfig ("hasRole(\"ADMIN\")");
// within ADMIN the server decides the visible scope slice on every read —
// organization-wide plus rows marked legacy/unassigned for an
// ORGANIZATION-scope ADMIN, its own branch for a BRANCH-scope ADMIN, its own
// department for a DEPARTMENT-scope ADMIN — so no client filter can ever
// widen a view; a foreign branch filter simply answers the empty set. The
// adapter adds no client-side authorization of its own and the screen
// consuming it displays only evidence fields, never tokens, credentials,
// or request bodies. The adapter sends exactly the shared apiFetch envelope
// (Bearer token, ApiError, onUnauthorized) and never a body — components
// never call fetch directly.

// The four server filters (docs/plan3.md Task 11). Blank or absent values
// are omitted entirely, so the server treats them as absent — never as
// empty-string matches.
function auditQuery(filters) {
  const params = new URLSearchParams();
  for (const key of ['branchId', 'resourceType', 'actor', 'correlationId']) {
    const value = filters?.[key];
    if (typeof value === 'string' && value.trim() !== '') {
      params.set(key, value.trim());
    }
  }
  const query = params.toString();
  return query === '' ? '' : '?' + query;
}

export function fetchAuditEvents({ token, onUnauthorized, filters } = {}) {
  return apiFetch('/api/audit' + auditQuery(filters), {
    method: 'GET',
    token,
    onUnauthorized,
  });
}
