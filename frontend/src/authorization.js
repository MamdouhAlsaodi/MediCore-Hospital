// Single source of truth for UI capability hints (plan1.md Task 9).
//
// Pure data plus one predicate. No side effects, no transport. Everything
// here is a convenience boundary for the interface only: it decides which
// actions and destinations the UI offers. It NEVER authorizes anything —
// the backend policy in backend/.../auth/SecurityConfig.java stays
// deny-by-default and authoritative for every request (401 on an expired
// or absent session, 403 on a role the path rules refuse).
//
// Implemented UI actions only (plan1.md Tasks 6-8):
//   patient read       GET  /api/patients, GET /api/patients/{id}
//                      (list/search screen and detail view)
//   patient create     POST /api/patients       (the "New patient" action)
//   patient update     PUT  /api/patients/{id}  (the "Edit record" action)
//   patient formView   read-only inspection of the record form
//                      (the "View record form" action)
//   appointment read   GET  /api/appointments   (the appointments list)
//   appointment create POST /api/appointments   (the "Schedule appointment" action)
//
// Documented policy gaps — asserted honestly in authorization.test.js and
// SecurityAuthorizationTest.java; backend alignment needs its own packet:
//   - Patient create/update: the product convention is RECEPTIONIST/ADMIN
//     (DOCTOR gets a read-only form view), but SecurityConfig enforces only
//     the four-role path rule for /api/patients/** with no method-level
//     distinction — a direct DOCTOR/NURSE API write would succeed. The UI
//     hiding the action is convenience only.
//   - Appointment create: SecurityConfig admits DOCTOR/NURSE at the path
//     level while the UI hides the action from them; their direct API
//     scheduling calls would succeed.
//   - Staff directory: /api/staff/** is ADMIN/HR only, so RECEPTIONIST — the
//     primary scheduling role — receives 403 for the professional directory
//     the scheduling form depends on (the form renders an honest disabled
//     state; see the Task 8 report).

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
