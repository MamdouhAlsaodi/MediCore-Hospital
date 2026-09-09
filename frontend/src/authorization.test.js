import { describe, expect, it } from 'vitest';
import { PERMISSIONS, can } from './authorization.js';

// plan1.md Task 9: the resource/action permission map for the implemented UI
// actions only (patient read/create/update, appointment read/create). This is
// the capability map the interface hints with; the backend policy in
// backend/.../auth/SecurityConfig.java stays deny-by-default and is the only
// authority for what a request may actually do (401 expired session, 403
// refused role). These tests pin the map so the UI hint surface cannot drift
// silently from the documented matrix.
const sessionFor = (...roles) => ({ token: 'synthetic-token', username: 'testuser', roles });

// Frontend capability matrix (UI hints only — convenience gating).
const PATIENT_MATRIX = {
  // Patient read backs the list/search screen and the detail view.
  ADMIN: { read: true, create: true, update: true, formView: true },
  RECEPTIONIST: { read: true, create: true, update: true, formView: true },
  // DOCTOR inspects the record form read-only but never mutates.
  DOCTOR: { read: true, create: false, update: false, formView: true },
  NURSE: { read: true, create: false, update: false, formView: false },
};

const APPOINTMENT_MATRIX = {
  ADMIN: { read: true, create: true },
  RECEPTIONIST: { read: true, create: true },
  // DOCUMENTED POLICY MISMATCH (frontend side): the UI hides scheduling from
  // DOCTOR/NURSE, but SecurityConfig admits every one of the four roles at
  // the path level for POST /api/appointments — a direct API call from those
  // roles would succeed. The UI hint is stricter than the server; server
  // enforcement stays authoritative. Backend alignment needs its own packet;
  // SecurityAuthorizationTest.java pins the server side of this gap.
  DOCTOR: { read: true, create: false },
  NURSE: { read: true, create: false },
};

const ACTIONS_PER_RESOURCE = {
  patient: ['read', 'create', 'update', 'formView'],
  appointment: ['read', 'create'],
};

describe('authorization permission map (plan1.md Task 9)', () => {
  it('grants patient actions exactly per the documented matrix', () => {
    for (const [role, expected] of Object.entries(PATIENT_MATRIX)) {
      for (const action of ACTIONS_PER_RESOURCE.patient) {
        expect(can(sessionFor(role), action, 'patient'), `${role} can ${action} patient`).toBe(
          expected[action],
        );
      }
    }
  });

  it('grants appointment actions exactly per the documented matrix', () => {
    for (const [role, expected] of Object.entries(APPOINTMENT_MATRIX)) {
      for (const action of ACTIONS_PER_RESOURCE.appointment) {
        expect(
          can(sessionFor(role), action, 'appointment'),
          `${role} can ${action} appointment`,
        ).toBe(expected[action]);
      }
    }
  });

  it('denies every implemented action to a role outside the clinical-front-desk set', () => {
    const session = sessionFor('BILLING');
    for (const [resource, actions] of Object.entries(ACTIONS_PER_RESOURCE)) {
      for (const action of actions) {
        expect(can(session, action, resource), `BILLING can ${action} ${resource}`).toBe(false);
      }
    }
  });

  it('denies every implemented action to an unknown role', () => {
    const session = sessionFor('UNKNOWN_ROLE');
    for (const [resource, actions] of Object.entries(ACTIONS_PER_RESOURCE)) {
      for (const action of actions) {
        expect(can(session, action, resource), `unknown role can ${action} ${resource}`).toBe(false);
      }
    }
  });

  it('denies by default for missing sessions, empty role lists, and unknown questions', () => {
    for (const session of [undefined, null, {}, { token: 't', username: 'u' }, sessionFor()]) {
      expect(can(session, 'read', 'patient')).toBe(false);
      expect(can(session, 'create', 'appointment')).toBe(false);
    }
    expect(can(sessionFor('ADMIN'), 'delete', 'patient')).toBe(false);
    expect(can(sessionFor('ADMIN'), 'read', 'audit')).toBe(false);
    expect(can(sessionFor('ADMIN'), 'nonexistent-action', 'patient')).toBe(false);
  });

  it('keeps the read sets exactly on the four backend-admitted roles', () => {
    // These role sets feed the shell navigation too; widening them would hint
    // at screens the server refuses with 403.
    expect(PERMISSIONS.patient.read).toEqual(['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']);
    expect(PERMISSIONS.appointment.read).toEqual(['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']);
  });
});
