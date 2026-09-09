// Single source of truth for UI capability hints (plan1.md Task 9).
//
// Pure data plus one predicate. No side effects, no transport. Everything
// here is a convenience boundary for the interface only: it decides which
// actions and destinations the UI offers. It NEVER authorizes anything —
// the backend policy in backend/.../auth/SecurityConfig.java stays
// deny-by-default and authoritative for every request (401 on an expired
// or absent session, 403 on a role the path rules refuse).
//
// Implemented UI actions only (plan1.md Tasks 6-10):
//   patient read       GET  /api/patients, GET /api/patients/{id}
//                      (list/search screen and detail view)
//   patient create     POST /api/patients       (the "New patient" action)
//   patient update     PUT  /api/patients/{id}  (the "Edit record" action)
//   patient formView   read-only inspection of the record form
//                      (the "View record form" action)
//   appointment read   GET  /api/appointments   (the appointments list)
//   appointment create POST /api/appointments   (the "Schedule appointment" action)
//   audit read         GET  /api/audit          (the ADMIN audit evidence screen)
//
// The map mirrors the server-enforced matrix as of the Task 9 alignment
// (commit b49e549, PR #4): method-level rules in SecurityConfig restrict
// patient create/update and appointment create to ADMIN and RECEPTIONIST —
// exactly the roles granted here — so a direct API write from DOCTOR or
// NURSE is refused server-side with 403 and no implemented write action
// depends solely on frontend hiding. The staff-directory read behind the
// scheduling form admits RECEPTIONIST server-side (GET /api/staff ->
// ADMIN/HR/RECEPTIONIST). The audit entry mirrors the Task 10 ADMIN-only
// rule on /api/audit**. Both layers are pinned: authorization.test.js on
// this map and SecurityAuthorizationTest.java on the server side.

export const PERMISSIONS = {
  patient: {
    read: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
    create: ['ADMIN', 'RECEPTIONIST'],
    update: ['ADMIN', 'RECEPTIONIST'],
    formView: ['ADMIN', 'RECEPTIONIST', 'DOCTOR'],
  },
  appointment: {
    read: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
    create: ['ADMIN', 'RECEPTIONIST'],
  },
  // Audit evidence stays ADMIN-only, mirroring the SecurityConfig rule
  // ("hasRole(\"ADMIN\")" on /api/audit/**) — no role may widen it.
  audit: {
    read: ['ADMIN'],
  },
};

// can(session, action, resource) — the only capability question the UI asks.
// Unknown resources/actions and missing or empty role lists deny by default,
// so a malformed session can never widen what the interface offers.
export function can(session, action, resource) {
  const permitted = PERMISSIONS[resource]?.[action];
  if (!Array.isArray(permitted)) return false;
  const roles = session?.roles;
  if (!Array.isArray(roles) || roles.length === 0) return false;
  return permitted.some((role) => roles.includes(role));
}
