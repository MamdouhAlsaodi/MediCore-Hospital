import { ApiError, apiFetch } from '../../api.js';
// Phase 5 US1 (T043; FR-017): the request descriptor comes from the
// generated typed contract; this module owns the hierarchy response's
// strict display parse. Transport only — no UI, no state.
import { getAuthorizedNetworkHierarchy as getAuthorizedNetworkHierarchyHttpRequest } from '../../generated/api/index';

// Authorized network-hierarchy transport (Phase 5 US1): the only client
// path to GET /api/network/hierarchy. The endpoint takes no parameters —
// the server derives the authorized network → hospital → branch slice from
// the acting context alone — so this adapter sends nothing but the bearer
// token and never accepts or injects hierarchy identifiers. The response is
// a strict allowlist parse: a malformed payload refuses to render (ApiError
// 500) instead of improvising, an explicit false active flag and an empty
// branch list are preserved honestly, and unknown keys are dropped so no
// server-side field can leak into a future render by accident. Deterministic
// ordering is never re-sorted here — the server owns the order.
const malformed = () => new ApiError(500, 'The server returned an unexpected network hierarchy.');

function isNonEmptyString(value) {
  return typeof value === 'string' && value.length > 0;
}

function parseBranchView(payload, hospitalId) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw malformed();
  if (!isNonEmptyString(payload.id) || !isNonEmptyString(payload.hospitalId)
      || !isNonEmptyString(payload.code) || !isNonEmptyString(payload.name)
      || !isNonEmptyString(payload.timeZone)
      || typeof payload.active !== 'boolean') {
    throw malformed();
  }
  // A branch view must declare the hospital it actually belongs to.
  if (payload.hospitalId !== hospitalId) throw malformed();
  return {
    id: payload.id,
    hospitalId: payload.hospitalId,
    code: payload.code,
    name: payload.name,
    timeZone: payload.timeZone,
    active: payload.active,
  };
}

function parseHospitalView(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw malformed();
  if (!isNonEmptyString(payload.id) || !isNonEmptyString(payload.code)
      || !isNonEmptyString(payload.name) || !isNonEmptyString(payload.regionLabel)
      || !isNonEmptyString(payload.timeZone)
      || typeof payload.active !== 'boolean'
      || !Array.isArray(payload.branches)) {
    throw malformed();
  }
  return {
    id: payload.id,
    code: payload.code,
    name: payload.name,
    regionLabel: payload.regionLabel,
    timeZone: payload.timeZone,
    active: payload.active,
    branches: payload.branches.map((branch) => parseBranchView(branch, payload.id)),
  };
}

/** Strict allowlist of the authorized network hierarchy; server order is kept verbatim. */
export function parseNetworkHierarchy(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw malformed();
  if (!isNonEmptyString(payload.organizationId) || !isNonEmptyString(payload.organizationCode)
      || !isNonEmptyString(payload.organizationName)
      || !Array.isArray(payload.hospitals)) {
    throw malformed();
  }
  return {
    organizationId: payload.organizationId,
    organizationCode: payload.organizationCode,
    organizationName: payload.organizationName,
    hospitals: payload.hospitals.map(parseHospitalView),
  };
}

/**
 * Fetches the authorized network hierarchy of the acting context through
 * the shared fetch boundary. Takes no hierarchy identifiers: the slice is
 * entirely server-derived (client-supplied hospital or branch references
 * are never accepted as authorization evidence).
 */
export async function fetchNetworkHierarchy({ token, onUnauthorized } = {}) {
  const request = getAuthorizedNetworkHierarchyHttpRequest();
  return apiFetch(request.path, { method: request.method, token, onUnauthorized })
    .then((payload) => parseNetworkHierarchy(payload));
}
