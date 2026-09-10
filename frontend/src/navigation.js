// Pure navigation registry + role filter. No router, no side effects.
//
// The destination role sets come from the shared permission map in
// authorization.js — the single source of truth for UI capability hints
// (plan1.md Task 9). UI filtering is a convenience boundary only: it decides
// which destinations the shell offers. It never authorizes anything — every
// API request stays protected by the backend policy in
// backend/.../auth/SecurityConfig.java, which these role sets mirror:
//   - /api/dashboard/**     -> authenticated() (any logged-in role)
//   - /api/patients/**      -> ADMIN, DOCTOR, NURSE, RECEPTIONIST
//   - /api/appointments/**  -> ADMIN, DOCTOR, NURSE, RECEPTIONIST
//   - /api/admissions/**    -> ADMIN, DOCTOR, NURSE, RECEPTIONIST (plan2.md Task 2)
//   - /api/emergency-visits/** -> ADMIN, DOCTOR, NURSE, RECEPTIONIST (plan2.md Task 3)
//   - /api/audit/**         -> ADMIN
import { PERMISSIONS } from './authorization.js';

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
    roles: PERMISSIONS.patient.read,
    implemented: true,
  },
  {
    id: 'appointments',
    label: 'Appointments',
    heading: 'Appointments',
    roles: PERMISSIONS.appointment.read,
    implemented: true,
  },
  {
    id: 'admissions',
    label: 'Admissions',
    heading: 'Admissions',
    roles: PERMISSIONS.admission.read,
    implemented: true,
  },
  {
    id: 'emergency-visits',
    label: 'Emergency Visits',
    heading: 'Emergency Visits',
    roles: PERMISSIONS.emergencyVisit.read,
    implemented: true,
  },
  {
    id: 'audit',
    label: 'Audit',
    heading: 'Audit Evidence',
    roles: PERMISSIONS.audit.read,
    implemented: true,
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
