const SESSION_KEY = 'medicore.session';
const LEGACY_TOKEN_KEY = 'token';

export function purgeLegacyStorage() {
  try {
    localStorage.removeItem(LEGACY_TOKEN_KEY);
  } catch {
    // Storage unavailable (private mode etc.) — nothing to purge.
  }
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.length > 0;
}

function isOptionalNonEmptyString(value) {
  return value === null || value === undefined || isNonEmptyString(value);
}

// Strict allowlist of one server-issued assignment view (docs/plan3.md
// Task 3): ids/labels plus one role and one scope. Only the fields the
// shell and selector render are required; anything the server lists must
// at least carry a usable identity.
function isValidAssignmentView(value) {
  return Boolean(value)
    && isNonEmptyString(value.id)
    && isNonEmptyString(value.role)
    && isNonEmptyString(value.scope)
    && isNonEmptyString(value.organizationId)
    && value.enabled === true
    && isOptionalNonEmptyString(value.hospitalId)
    && isOptionalNonEmptyString(value.hospitalLabel)
    && isOptionalNonEmptyString(value.branchId)
    && isOptionalNonEmptyString(value.branchLabel)
    && isOptionalNonEmptyString(value.departmentId)
    && isOptionalNonEmptyString(value.departmentLabel);
}

function assignmentMatchesContext(assignment, context) {
  if (assignment.role !== context.role || assignment.scope !== context.scope) return false;
  if (assignment.organizationId !== context.organizationId) return false;
  if ((assignment.departmentId ?? null) !== (context.departmentId ?? null)) return false;
  if (assignment.scope === 'ORGANIZATION') return true;
  // A fixed-scope assignment (HOSPITAL, BRANCH, DEPARTMENT) owns exactly one
  // hospital (Phase 5, FR-008): a context naming a different one is tampered
  // or stale and fails the reload closed. When either side omits the field
  // (the browser login parser stores the context without it) the identity
  // resolves through the assignment below — never invented here.
  if (assignment.hospitalId && context.hospitalId && assignment.hospitalId !== context.hospitalId) {
    return false;
  }
  return assignment.branchId === context.branchId;
}

// Strict allowlist of the selected acting context: the assignment pointer,
// the one acting role/scope, and the bound organization/branch plus the
// optional department. The branch id is always present — the server
// resolves every scope to a concrete active branch.
function isValidActingContext(value) {
  return Boolean(value)
    && isNonEmptyString(value.username)
    && isNonEmptyString(value.assignmentId)
    && isNonEmptyString(value.role)
    && isNonEmptyString(value.scope)
    && isNonEmptyString(value.organizationId)
    && isOptionalNonEmptyString(value.hospitalId)
    && isNonEmptyString(value.branchId)
    && isOptionalNonEmptyString(value.departmentId);
}

// A session that carries an acting context is valid only when the whole
// server-issued structure is present and self-consistent: a non-empty
// assignment list that actually contains the acting assignment. A branch
// id in storage alone is never authority without the matching
// context-bound token and assignment list.
function isCompleteActingSession(session) {
  if (!isValidActingContext(session.actingContext)) return false;
  if (!Array.isArray(session.assignments) || session.assignments.length === 0) return false;
  if (!session.assignments.every(isValidAssignmentView)) return false;
  if (session.actingContext.username !== session.username) return false;
  if (!session.roles.includes(session.actingContext.role)) return false;
  const actingAssignment = session.assignments.find(
    (assignment) => assignment.id === session.actingContext.assignmentId
  );
  return Boolean(actingAssignment) && assignmentMatchesContext(actingAssignment, session.actingContext);
}

export function loadSession() {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsedSession = JSON.parse(raw);
    if (!parsedSession || typeof parsedSession.token !== 'string' || !parsedSession.token) return null;
    if (typeof parsedSession.username !== 'string' || !parsedSession.username) return null;
    if (!Array.isArray(parsedSession.roles)) return null;
    // Plan 3 Task 5: once a session carries an acting context, the reload
    // must restore a valid complete server-issued session structure — any
    // missing, malformed, or inconsistent piece (token, acting context, or
    // the assignment that context points at) rejects the whole session.
    // Sessions without an acting-context field keep the earlier acceptance
    // so a plain token/username/roles session still round-trips; such a
    // session carries no branch claim, so no branch authority is implied
    // and every request stays validated by the server on its token alone.
    if (parsedSession.actingContext !== undefined && !isCompleteActingSession(parsedSession)) return null;
    return parsedSession;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession() {
  sessionStorage.removeItem(SESSION_KEY);
}

// The complete identity of the acting context (Phase 5, FR-008/FR-009):
// the assignment pointer plus the hospital, branch, and department the
// server bound it to. The hospital resolves from the acting context when
// the session records it there and from the acting assignment for the
// fixed scopes (HOSPITAL, BRANCH, DEPARTMENT) that own their hospital —
// covering sessions stored by the frozen login parser, whose context
// record omits the field. An ORGANIZATION context's hospital is branch-
// specific and only known where the session carries it; no client-side
// lookup ever invents one. Returns null when the session carries no
// usable acting context.
export function actingContextIdentity(session) {
  const context = session?.actingContext;
  if (!context?.assignmentId || !context?.branchId) return null;
  const assignment = actingAssignmentOf(session);
  return {
    assignmentId: context.assignmentId,
    hospitalId: context.hospitalId ?? null,
    branchId: context.branchId,
    departmentId: context.departmentId ?? null,
    resolvedHospitalId: context.hospitalId
      ?? (assignment && assignment.scope !== 'ORGANIZATION' ? assignment.hospitalId ?? null : null),
  };
}

// Stable identity of the acting context: the complete assignment +
// hospital + branch + department chain (Phase 5, FR-009). Screens use it
// to reload context-bound data after a successful switch; sessions without
// an acting context share one neutral key. A context whose hospital
// differs never shares a key with one whose hospital matches, so data
// loaded under one hospital cannot survive a switch into another even
// when every other coordinate is identical.
export function actingContextKey(session) {
  const identity = actingContextIdentity(session);
  if (!identity) return 'no-acting-context';
  const hospitalId = identity.resolvedHospitalId ?? '';
  return `${identity.assignmentId}:${hospitalId}:${identity.branchId}:${identity.departmentId ?? ''}`;
}

// The server-issued assignment view the acting context points at, or null
// when the session carries no (consistent) acting context. Presentation
// layers resolve display labels through this lookup only — the client
// never invents branch or department authority.
export function actingAssignmentOf(session) {
  const context = session?.actingContext;
  if (!context?.assignmentId) return null;
  const assignments = Array.isArray(session?.assignments) ? session.assignments : [];
  return assignments.find((assignment) => assignment?.id === context.assignmentId) ?? null;
}
