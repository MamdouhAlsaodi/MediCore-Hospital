export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

function messageFor(status) {
  if (status === 401) return 'Your session has expired. Please log in again.';
  if (status === 403) return 'You do not have permission to view this data.';
  return 'The request failed (' + status + '). Please try again.';
}

export async function apiFetch(path, { method = 'GET', token, body, onUnauthorized } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Network error: the server is unreachable.');
  }

  if (response.status === 401 && onUnauthorized) onUnauthorized();
  if (!response.ok) throw new ApiError(response.status, messageFor(response.status));
  return response.json();
}

// Strict allowlist of the server-issued acting-context session response
// (docs/plan3.md Task 3 shape, consumed by the Task 5 shell): accessToken,
// Bearer token type, username, the single selected role, the deterministic
// enabled-assignment list, and the selected acting context. Returns the
// normalized session stored by the app — accessToken mapped to token — or
// throws ApiError(500) when the server response is not the expected shape.
// The acting assignment must be listed, so the shell/selector can resolve
// every label from server data and never invent branch authority.
export function parseActingSessionPayload(payload) {
  const isNonEmptyString = (value) => typeof value === 'string' && value.length > 0;
  const isOptionalNonEmptyString = (value) => value === null || value === undefined || isNonEmptyString(value);

  const malformed = () => new ApiError(500, 'The server returned an unexpected session response.');
  if (!payload || typeof payload !== 'object') throw malformed();
  if (!isNonEmptyString(payload.accessToken)) throw malformed();
  if (payload.tokenType !== 'Bearer') throw malformed();
  if (!isNonEmptyString(payload.username)) throw malformed();
  if (!Array.isArray(payload.roles) || payload.roles.length === 0
      || !payload.roles.every(isNonEmptyString)) throw malformed();
  if (!Array.isArray(payload.assignments) || payload.assignments.length === 0) throw malformed();
  for (const assignment of payload.assignments) {
    if (!assignment || typeof assignment !== 'object') throw malformed();
    if (!isNonEmptyString(assignment.id) || !isNonEmptyString(assignment.role)
        || !isNonEmptyString(assignment.scope) || !isNonEmptyString(assignment.organizationId)
        || assignment.enabled !== true
        || !isOptionalNonEmptyString(assignment.branchId)
        || !isOptionalNonEmptyString(assignment.branchLabel)
        || !isOptionalNonEmptyString(assignment.departmentId)
        || !isOptionalNonEmptyString(assignment.departmentLabel)) throw malformed();
  }
  const context = payload.actingContext;
  if (!context || typeof context !== 'object') throw malformed();
  if (!isNonEmptyString(context.assignmentId) || !isNonEmptyString(context.role)
      || !isNonEmptyString(context.scope) || !isNonEmptyString(context.organizationId)
      || !isNonEmptyString(context.branchId)
      || !isOptionalNonEmptyString(context.departmentId)) throw malformed();
  const actingAssignment = payload.assignments.find((assignment) => assignment.id === context.assignmentId);
  if (!actingAssignment) throw malformed();
  const fixedBranchMismatch = actingAssignment.scope !== 'ORGANIZATION'
    && actingAssignment.branchId !== context.branchId;
  if (context.username !== undefined && context.username !== payload.username) throw malformed();
  if (!payload.roles.includes(context.role)) throw malformed();
  if (actingAssignment.role !== context.role || actingAssignment.scope !== context.scope
      || actingAssignment.organizationId !== context.organizationId
      || (actingAssignment.departmentId ?? null) !== (context.departmentId ?? null)
      || fixedBranchMismatch) throw malformed();

  return {
    token: payload.accessToken,
    username: payload.username,
    roles: payload.roles,
    assignments: payload.assignments,
    actingContext: {
      username: context.username ?? payload.username,
      assignmentId: context.assignmentId,
      role: context.role,
      scope: context.scope,
      organizationId: context.organizationId,
      branchId: context.branchId,
      departmentId: context.departmentId ?? null,
    },
  };
}

// Full login for the acting-context shell (docs/plan3.md Task 5): verifies
// the credentials and keeps the complete server-issued session — assignments
// plus the selected acting context — so the shell can display the acting
// role/branch/department and offer the server-issued switch targets. The
// failure surface is the same non-enumerating ApiError for every bad status.
export async function authenticate(username, password) {
  let response;
  try {
    response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    throw new ApiError(0, 'Network error: the server is unreachable.');
  }
  if (!response.ok) throw new ApiError(response.status, 'Invalid username or password.');
  const payload = await response.json();
  return parseActingSessionPayload(payload);
}
