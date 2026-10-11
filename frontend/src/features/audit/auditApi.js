import { ApiError, apiFetch } from '../../api.js';
// Phase 5 US7 (T137): the request descriptor comes from the generated typed
// contract; this module owns the strict audit-evidence parse — the extended
// nineteen-key AuditEventView allowlist (the fifteen legacy evidence keys
// plus hospitalId, transferId, sourceHospitalId, destinationHospitalId) and
// the authorized hospital-context branch options.
import { listAuditEvents as listAuditEventsHttpRequest, getAuthorizedNetworkHierarchy as getAuthorizedNetworkHierarchyHttpRequest } from '../../generated/api/index';

// The only audit transport adapter (plan1.md Task 10; plan3.md Task 11;
// Phase 5 US7 T137). Strictly read-only:
//
//   list: GET /api/audit?branchId&resourceType&actor&correlationId
//         -> AuditEventView[] as the strict nineteen-key server DTO
//            allowlist (the fifteen legacy keys plus the bounded Phase 5
//            hospital/transfer context) — the JPA entity and its
//            persistence metadata are never exposed.
//
// /api/audit/** is ADMIN-only in backend SecurityConfig ("hasRole(\"ADMIN\")");
// within ADMIN the server decides the visible scope slice on every read —
// organization-wide plus rows marked legacy/unassigned for an
// ORGANIZATION-scope ADMIN, the hospital-wide slice for a HOSPITAL-scope
// ADMIN, its own branch for a BRANCH-scope ADMIN, its own department for a
// DEPARTMENT-scope ADMIN — so no client filter can ever widen a view; a
// foreign branch filter simply answers the empty set. The adapter adds no
// client-side authorization of its own and the screen consuming it displays
// only evidence fields, never tokens, credentials, or request bodies. The
// adapter sends exactly the shared apiFetch envelope (Bearer token, ApiError,
// onUnauthorized) and never a body — components never call fetch directly.

const malformed = () => new ApiError(500, 'The server returned an unexpected audit event list.');

function isNonEmptyString(value) {
  return typeof value === 'string' && value.length > 0;
}

function isOptionalNonEmptyString(value) {
  return value === null || value === undefined || (typeof value === 'string' && value.length > 0);
}

/**
 * Strict allowlist parse of one audit evidence row. Unknown keys are dropped
 * so no server-side field can leak into a future render by accident; a
 * missing required evidence key refuses the whole payload instead of
 * improvising a partial row.
 */
function parseAuditEvent(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw malformed();
  for (const key of ['id', 'action', 'actor', 'occurredAt']) {
    if (!isNonEmptyString(payload[key])) throw malformed();
  }
  for (const key of ['resourceType', 'resourceId', 'details', 'role', 'scope', 'correlationId']) {
    if (!isOptionalNonEmptyString(payload[key])) throw malformed();
  }
  for (const key of ['assignmentId', 'organizationId', 'branchId', 'departmentId',
    'hospitalId', 'transferId', 'sourceHospitalId', 'destinationHospitalId']) {
    if (!isOptionalNonEmptyString(payload[key])) throw malformed();
  }
  if (payload.branchAttribution !== null && !isNonEmptyString(payload.branchAttribution)) throw malformed();
  return {
    id: payload.id,
    actor: payload.actor,
    action: payload.action,
    resourceType: payload.resourceType ?? null,
    resourceId: payload.resourceId ?? null,
    details: payload.details ?? null,
    occurredAt: payload.occurredAt,
    assignmentId: payload.assignmentId ?? null,
    role: payload.role ?? null,
    scope: payload.scope ?? null,
    organizationId: payload.organizationId ?? null,
    branchId: payload.branchId ?? null,
    departmentId: payload.departmentId ?? null,
    correlationId: payload.correlationId ?? null,
    branchAttribution: payload.branchAttribution ?? null,
    hospitalId: payload.hospitalId ?? null,
    transferId: payload.transferId ?? null,
    sourceHospitalId: payload.sourceHospitalId ?? null,
    destinationHospitalId: payload.destinationHospitalId ?? null,
  };
}

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

export async function fetchAuditEvents({ token, onUnauthorized, filters } = {}) {
  const request = listAuditEventsHttpRequest();
  return apiFetch(request.path + auditQuery(filters), {
    method: request.method,
    token,
    onUnauthorized,
  }).then((payload) => {
    if (!Array.isArray(payload)) throw malformed();
    return payload.map(parseAuditEvent);
  });
}

/**
 * The authorized hospital context options for a HOSPITAL-scope session
 * (Phase 5 US7 T137): the acting hospital's own branch slice, read from the
 * server-owned network hierarchy (GET /api/network/hierarchy). Nothing is
 * ever constructed client-side — the hospital identity comes only from the
 * acting context and the branch options only from the returned slice, so a
 * foreign hospital's branches can never enter the filter by accident. A
 * failed or narrowed read degrades honestly: the caller decides between the
 * empty options list and a notice.
 */
export async function fetchHospitalBranchOptions({ token, hospitalId, onUnauthorized } = {}) {
  if (!isNonEmptyString(hospitalId)) {
    return [];
  }
  const request = getAuthorizedNetworkHierarchyHttpRequest();
  const hierarchy = await apiFetch(request.path, { method: request.method, token, onUnauthorized });
  if (!hierarchy || typeof hierarchy !== 'object' || !Array.isArray(hierarchy.hospitals)) {
    throw malformed();
  }
  const actingHospital = hierarchy.hospitals.find((hospital) => hospital?.id === hospitalId);
  if (!actingHospital || !Array.isArray(actingHospital.branches)) {
    return [];
  }
  return actingHospital.branches
    .filter((branch) => branch && isNonEmptyString(branch.id) && branch.hospitalId === hospitalId)
    .map((branch) => ({ id: branch.id, name: isNonEmptyString(branch.name) ? branch.name : branch.id }));
}
