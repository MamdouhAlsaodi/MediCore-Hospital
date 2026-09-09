// Pure navigation registry + role filter. No router, no side effects.
//
// UI filtering is a convenience boundary only: it decides which destinations
// the shell offers. It never authorizes anything — every API request stays
// protected by the backend policy in
// backend/.../auth/SecurityConfig.java, which these role sets mirror:
//   - /api/dashboard/**     -> authenticated() (any logged-in role)
//   - /api/patients/**      -> ADMIN, DOCTOR, NURSE, RECEPTIONIST
//   - /api/appointments/**  -> ADMIN, DOCTOR, NURSE, RECEPTIONIST

const DESTINATIONS = [
  {
    id: 'dashboard',
    label: 'Dashboard',
    heading: 'Operations Dashboard',
    roles: 'ANY',
    implemented: true,
  },
  {
    id: 'patients',
    label: 'Patients',
    heading: 'Patients',
    roles: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
    implemented: false,
    arrivesIn: 'Task 6',
  },
  {
    id: 'appointments',
    label: 'Appointments',
    heading: 'Appointments',
    roles: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
    implemented: false,
    arrivesIn: 'Task 8',
  },
];

export function canViewDestination(destination, roles) {
  if (!Array.isArray(roles) || roles.length === 0) return false;
  if (destination.roles === 'ANY') return true;
  return destination.roles.some((role) => roles.includes(role));
}

export function permittedDestinations(roles) {
  return DESTINATIONS.filter((destination) => canViewDestination(destination, roles));
}

// In-memory selection only: a fresh mount (page refresh) falls back to the
// dashboard because nothing is persisted and there is no URL routing.
export function defaultDestination() {
  return DESTINATIONS[0];
}
