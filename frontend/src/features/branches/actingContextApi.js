import { ApiError, parseActingSessionPayload } from '../../api.js';
// Phase 4 (plan Task 10, T073; FR-017): the request shape comes from the
// generated typed contract; this module keeps ownership of the switch's
// exact failure surface (its 403 keeps the prior context valid with its own
// controlled message, distinct from the shared generic permission error).
import { switchActingContext as switchActingContextHttpRequest } from '../../generated/api/index';

// Acting-context transport (docs/plan3.md Task 5): the only client path to
// POST /api/auth/context. The request carries the target assignment id AND
// the concrete hospital/branch pair selected in the UI (Phase 5 T050/T051:
// the server matches a supplied hospital against its own derived chain and
// refuses foreign or inactive pairs). For ORGANIZATION- and HOSPITAL-scope
// targets the pair comes from the server's own network hierarchy
// (GET /api/network/hierarchy via fetchNetworkHierarchy), so switching to
// another hospital/branch is a single request naming all three ids — the
// server never silently picks a target behind the UI's back. For
// BRANCH/DEPARTMENT targets the only pair ever sent is the one the
// server-issued assignment fixes. The response is validated with
// the same strict allowlist as login, so a successful switch always yields
// a complete replacement session: new context-bound token, updated acting
// context, and the current assignment list. Components never call fetch
// directly.
export async function switchContext({ token, assignmentId, hospitalId, branchId, onUnauthorized } = {}) {
  const body = { assignmentId };
  // Only server-issued ids are ever sent; absent/null lets the server fail
  // closed on fixed scopes, and the selector always resolves a concrete
  // hospital/branch pair for a selection target before calling.
  if (hospitalId) body.hospitalId = hospitalId;
  if (branchId) body.branchId = branchId;

  const request = switchActingContextHttpRequest(body);
  let response;
  try {
    response = await fetch(request.path, {
      method: request.method,
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request.body),
    });
  } catch {
    throw new ApiError(0, 'Network error: the server is unreachable.');
  }

  if (response.status === 401) {
    // The session itself is gone: ownership of expiry is the shell's.
    if (onUnauthorized) onUnauthorized();
    throw new ApiError(401, 'Your session has expired. Please log in again.');
  }
  if (response.status === 403) {
    // Controlled refusal: the prior token/context stays valid and is kept.
    throw new ApiError(403, 'The server refused this context switch. Your current role and branch are unchanged.');
  }
  if (!response.ok) {
    throw new ApiError(response.status, 'The context switch failed (' + response.status + '). Your current role and branch are unchanged.');
  }

  const payload = await response.json();
  return parseActingSessionPayload(payload);
}

// Strict allowlist of the server-owned organization view consumed by the
// branch selector (backend Task 2 shape of GET /api/organization): identity
// and labels plus the ACTIVE branches in server order. Branch ids parsed
// here are the only branch authority the selector ever offers for an
// ORGANIZATION assignment; a branch id from storage, a request body, or a
// header is never one. Returns the normalized view or throws ApiError(500)
// when the server response is not the expected shape.
function parseOrganizationPayload(payload) {
  const isNonEmptyString = (value) => typeof value === 'string' && value.length > 0;
  const malformed = () => new ApiError(500, 'The server returned an unexpected organization response.');
  if (!payload || typeof payload !== 'object') throw malformed();
  if (!isNonEmptyString(payload.id) || !isNonEmptyString(payload.name)) throw malformed();
  if (!Array.isArray(payload.activeBranches)) throw malformed();
  for (const branch of payload.activeBranches) {
    if (!branch || typeof branch !== 'object') throw malformed();
    if (!isNonEmptyString(branch.id) || !isNonEmptyString(branch.organizationId)
        || !isNonEmptyString(branch.name) || branch.active !== true) throw malformed();
  }
  return {
    id: payload.id,
    name: payload.name,
    activeBranches: payload.activeBranches.map((branch) => ({
      id: branch.id,
      organizationId: branch.organizationId,
      code: branch.code ?? '',
      name: branch.name,
      locationLabel: branch.locationLabel ?? '',
      active: true,
    })),
  };
}

// Organization view transport for the selector (docs/plan3.md Task 5, the
// server-owned branch allowlist): the only client path to
// GET /api/organization. 401 is delegated to onUnauthorized — the shell owns
// session expiry; every other failure (403 included, the endpoint is
// ADMIN-authorized) throws ApiError and leaves the caller's session,
// context, and selection untouched so the UI can show text feedback.
export async function fetchOrganizationView({ token, onUnauthorized } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;

  let response;
  try {
    response = await fetch('/api/organization', { headers });
  } catch {
    throw new ApiError(0, 'Network error: the server is unreachable.');
  }

  if (response.status === 401) {
    if (onUnauthorized) onUnauthorized();
    throw new ApiError(401, 'Your session has expired. Please log in again.');
  }
  if (!response.ok) {
    throw new ApiError(response.status, 'Branch options could not be loaded (' + response.status + '). Your current role and branch are unchanged.');
  }

  const payload = await response.json();
  return parseOrganizationPayload(payload);
}
