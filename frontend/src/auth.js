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
    && isOptionalNonEmptyString(value.branchId)
    && isOptionalNonEmptyString(value.branchLabel)
    && isOptionalNonEmptyString(value.departmentId)
    && isOptionalNonEmptyString(value.departmentLabel);
}

function assignmentMatchesContext(assignment, context) {
  if (assignment.role !== context.role || assignment.scope !== context.scope) return false;
  if (assignment.organizationId !== context.organizationId) return false;
  if ((assignment.departmentId ?? null) !== (context.departmentId ?? null)) return false;
  return assignment.scope === 'ORGANIZATION' || assignment.branchId === context.branchId;
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

// Stable identity of the acting context: assignment pointer plus the bound
// branch. Screens use it to reload branch-scoped data after a successful
// context switch; sessions without an acting context share one neutral key.
export function actingContextKey(session) {
  const context = session?.actingContext;
  if (!context?.assignmentId || !context?.branchId) return 'no-acting-context';
  return `${context.assignmentId}:${context.branchId}`;
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
