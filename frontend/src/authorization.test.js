import { describe, expect, it } from 'vitest';
import { PERMISSIONS, can } from './authorization.js';

// plan1.md Task 9: the resource/action permission map for the implemented UI
// actions only (patient read/create/update, appointment read/create). This is
// the capability map the interface hints with; the backend policy in
// backend/.../auth/SecurityConfig.java stays deny-by-default and is the only
// authority for what a request may actually do (401 expired session, 403
// refused role). These tests pin the map so the UI hint surface cannot drift
// silently from the documented matrix.
// plan2.md Tasks 2-3 add the admission and emergencyVisit resources
// (read/create/transition), mirroring the unchanged server family rules on
// /api/admissions/** and /api/emergency-visits/**: all four
// clinical-administrative roles on every method — UI mirroring only. The
// emergency-visit triage label is a neutral 1-5 demo value with no clinical
// meaning. plan2.md Task 4 adds the invoice resource (read/create/
// transition), mirroring the server family rule on /api/invoices/**:
// ADMIN and BILLING only, and the whole family is a FINANCIAL SIMULATION
// with no real payments. plan3.md Task 6 adds the bed resource
// (read/create/transition), mirroring the unchanged server family rule on
// /api/beds/**: all four clinical-administrative roles, with the
// occupancy lifecycle admission-owned by Task 7.
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
  // The UI hint mirrors the enforced server policy: SecurityConfig admits
  // only ADMIN/RECEPTIONIST for POST /api/appointments (Task 9 write rule,
  // b49e549 / PR #4) — a direct API call from DOCTOR/NURSE is refused with
  // 403 and nothing persists (SecurityAuthorizationTest.java pins the server
  // side). Server enforcement stays the only authority; the hint matches it.
  DOCTOR: { read: true, create: false },
  NURSE: { read: true, create: false },
};

const ADMISSION_MATRIX = {
  // The server family rule on /api/admissions/** admits all four
  // clinical-administrative roles on every method and Task 2 changed no
  // role policy (plan2 §7.1 narrowing stays an owner decision), so the UI
  // hint grants register (create) and discharge (transition) exactly as
  // widely as read. A direct API call from any other role is refused with
  // 403 server-side (SecurityAuthorizationTest/CareOperationsApiTest pin it).
  ADMIN: { read: true, create: true, transition: true },
  DOCTOR: { read: true, create: true, transition: true },
  NURSE: { read: true, create: true, transition: true },
  RECEPTIONIST: { read: true, create: true, transition: true },
};

const EMERGENCY_VISIT_MATRIX = {
  // The server family rule on /api/emergency-visits/** admits all four
  // clinical-administrative roles on every method and Task 3 changed no
  // role policy (plan2 §7.1 narrowing stays an owner decision), so the UI
  // hint grants register (create) and status transitions exactly as widely
  // as read. A direct API call from any other role is refused with 403
  // server-side (SecurityAuthorizationTest/CareOperationsApiTest pin it).
  ADMIN: { read: true, create: true, transition: true },
  DOCTOR: { read: true, create: true, transition: true },
  NURSE: { read: true, create: true, transition: true },
  RECEPTIONIST: { read: true, create: true, transition: true },
};

const INVOICE_MATRIX = {
  // The server family rule on /api/invoices/** admits only ADMIN and
  // BILLING on every method (SecurityConfig, untouched by Task 4), so the
  // UI hint grants the invoice family to exactly those two roles. A direct
  // API call from any other role is refused with 403 server-side
  // (CareOperationsApiTest pins it). The invoice family is a FINANCIAL
  // SIMULATION — no real payments.
  ADMIN: { read: true, create: true, transition: true },
  BILLING: { read: true, create: true, transition: true },
  DOCTOR: { read: false, create: false, transition: false },
  NURSE: { read: false, create: false, transition: false },
  RECEPTIONIST: { read: false, create: false, transition: false },
};

const BED_MATRIX = {
  // The server family rule on /api/beds/** admits all four
  // clinical-administrative roles on every method and Task 6 changed no
  // role policy (plan narrowing stays an owner decision), so the UI hint
  // grants add (create) and status transitions exactly as widely as read.
  // A direct API call from any other role is refused with 403 server-side
  // (CareOperationsApiTest pins it). The lifecycle itself is server-owned:
  // only AVAILABLE/MAINTENANCE/OUT_OF_SERVICE are client transitions and
  // OCCUPIED is admission-owned (plan3.md Task 7), so no UI action can
  // ever send it.
  ADMIN: { read: true, create: true, transition: true },
  DOCTOR: { read: true, create: true, transition: true },
  NURSE: { read: true, create: true, transition: true },
  RECEPTIONIST: { read: true, create: true, transition: true },
};

const ACTIONS_PER_RESOURCE = {
  patient: ['read', 'create', 'update', 'formView'],
  appointment: ['read', 'create'],
  admission: ['read', 'create', 'transition'],
  emergencyVisit: ['read', 'create', 'transition'],
  invoice: ['read', 'create', 'transition'],
  bed: ['read', 'create', 'transition'],
  audit: ['read'],
};

// Every role outside the named families must be denied by default across
// the whole implemented map (plan2.md Task 6: dashboard-only roles never
// gain a care-operation or audit capability from the UI hint layer).
const DENY_BY_DEFAULT_ROLES = ['LAB_TECH', 'RADIOLOGY_TECH', 'PHARMACIST', 'HR', 'STAFF'];

describe('authorization permission map (plan1.md Task 9 + plan2.md Tasks 2-4)', () => {
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

  it('grants admission actions exactly per the documented four-role family matrix', () => {
    for (const [role, expected] of Object.entries(ADMISSION_MATRIX)) {
      for (const action of ACTIONS_PER_RESOURCE.admission) {
        expect(
          can(sessionFor(role), action, 'admission'),
          `${role} can ${action} admission`,
        ).toBe(expected[action]);
      }
    }
  });

  it('grants emergency-visit actions exactly per the documented four-role family matrix', () => {
    for (const [role, expected] of Object.entries(EMERGENCY_VISIT_MATRIX)) {
      for (const action of ACTIONS_PER_RESOURCE.emergencyVisit) {
        expect(
          can(sessionFor(role), action, 'emergencyVisit'),
          `${role} can ${action} emergencyVisit`,
        ).toBe(expected[action]);
      }
    }
  });

  it('grants invoice actions exactly per the documented ADMIN/BILLING matrix', () => {
    for (const [role, expected] of Object.entries(INVOICE_MATRIX)) {
      for (const action of ACTIONS_PER_RESOURCE.invoice) {
        expect(
          can(sessionFor(role), action, 'invoice'),
          `${role} can ${action} invoice`,
        ).toBe(expected[action]);
      }
    }
  });

  it('grants bed actions exactly per the documented four-role family matrix (plan3.md Task 6)', () => {
    for (const [role, expected] of Object.entries(BED_MATRIX)) {
      for (const action of ACTIONS_PER_RESOURCE.bed) {
        expect(
          can(sessionFor(role), action, 'bed'),
          `${role} can ${action} bed`,
        ).toBe(expected[action]);
      }
    }
  });

  it('denies every non-invoice action to BILLING, which the server isolates to invoices', () => {
    const session = sessionFor('BILLING');
    for (const [resource, actions] of Object.entries(ACTIONS_PER_RESOURCE)) {
      if (resource === 'invoice') continue;
      for (const action of actions) {
        expect(can(session, action, resource), `BILLING can ${action} ${resource}`).toBe(false);
      }
    }
    // plan2.md Task 4: BILLING is the second invoice role server-side, so
    // the UI hint grants the full invoice family to it.
    for (const action of ACTIONS_PER_RESOURCE.invoice) {
      expect(can(session, action, 'invoice'), `BILLING can ${action} invoice`).toBe(true);
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

  it('denies every implemented action to every deny-by-default role, including RADIOLOGY_TECH', () => {
    // plan2.md Task 6: LAB_TECH, RADIOLOGY_TECH, PHARMACIST, HR, and STAFF
    // hold no destination beyond the dashboard and no implemented action —
    // the hint layer must never invent one the server refuses with 403.
    for (const role of DENY_BY_DEFAULT_ROLES) {
      const session = sessionFor(role);
      for (const [resource, actions] of Object.entries(ACTIONS_PER_RESOURCE)) {
        for (const action of actions) {
          expect(can(session, action, resource), `${role} can ${action} ${resource}`).toBe(false);
        }
      }
    }
  });

  it('grants multi-role sessions the union of their single-role capabilities and nothing more', () => {
    // A session carries a role list; the predicate is a union over it.
    // Combining roles may add capabilities but can never widen past the
    // per-role sets (e.g. no combination below reaches audit read).
    const doctorBilling = sessionFor('DOCTOR', 'BILLING');
    expect(can(doctorBilling, 'read', 'invoice')).toBe(true); // via BILLING
    expect(can(doctorBilling, 'create', 'invoice')).toBe(true); // via BILLING
    expect(can(doctorBilling, 'transition', 'admission')).toBe(true); // via DOCTOR
    expect(can(doctorBilling, 'read', 'patient')).toBe(true); // via DOCTOR
    expect(can(doctorBilling, 'create', 'patient')).toBe(false); // neither role
    expect(can(doctorBilling, 'read', 'audit')).toBe(false); // neither role

    const receptionistBilling = sessionFor('RECEPTIONIST', 'BILLING');
    expect(can(receptionistBilling, 'create', 'patient')).toBe(true); // via RECEPTIONIST
    expect(can(receptionistBilling, 'transition', 'invoice')).toBe(true); // via BILLING
    expect(can(receptionistBilling, 'read', 'audit')).toBe(false);

    const nurseLabTech = sessionFor('NURSE', 'LAB_TECH');
    expect(can(nurseLabTech, 'create', 'emergencyVisit')).toBe(true); // via NURSE
    expect(can(nurseLabTech, 'read', 'invoice')).toBe(false); // LAB_TECH adds nothing
    expect(can(nurseLabTech, 'read', 'audit')).toBe(false);

    const adminPharmacist = sessionFor('ADMIN', 'PHARMACIST');
    expect(can(adminPharmacist, 'read', 'audit')).toBe(true); // via ADMIN
    expect(can(adminPharmacist, 'transition', 'invoice')).toBe(true); // via ADMIN
  });

  it('denies by default for missing sessions, empty role lists, unknown questions, and audit read outside ADMIN', () => {
    for (const session of [undefined, null, {}, { token: 't', username: 'u' }, sessionFor()]) {
      expect(can(session, 'read', 'patient')).toBe(false);
      expect(can(session, 'create', 'appointment')).toBe(false);
      expect(can(session, 'transition', 'admission')).toBe(false);
    }
    expect(can(sessionFor('ADMIN'), 'delete', 'patient')).toBe(false);
    // Task 10: audit read is now a known, ADMIN-only resource — ADMIN is
    // granted, every other role denies; a genuinely unknown resource
    // still denies by default.
    expect(can(sessionFor('ADMIN'), 'read', 'audit')).toBe(true);
    expect(can(sessionFor('DOCTOR'), 'read', 'audit')).toBe(false);
    expect(can(sessionFor('ADMIN'), 'read', 'nonexistent-resource')).toBe(false);
    expect(can(sessionFor('ADMIN'), 'nonexistent-action', 'patient')).toBe(false);
  });

  it('keeps the Task 11 role policy unchanged: audit read stays ADMIN-only and no family matrix widens', () => {
    // plan3.md Task 11 enriches the audit evidence with the acting context
    // and one bounded correlation id and makes the read scope-aware on the
    // server; it changes no role policy. The audit hint stays exactly the
    // one ADMIN-only read (no new audit actions are invented), and the
    // bed/admission owner decision (plan3 §8) was not made, so those
    // matrices remain exactly as the server family rules admit them.
    expect(PERMISSIONS.audit).toEqual({ read: ['ADMIN'] });
    expect(can(sessionFor('ADMIN'), 'read', 'audit')).toBe(true);
    for (const role of ['DOCTOR', 'NURSE', 'RECEPTIONIST', 'BILLING', ...DENY_BY_DEFAULT_ROLES]) {
      expect(can(sessionFor(role), 'read', 'audit'), `${role} can read audit`).toBe(false);
    }
    expect(PERMISSIONS.bed).toEqual({
      read: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
      create: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
      transition: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
    });
    expect(PERMISSIONS.admission).toEqual({
      read: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
      create: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
      transition: ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST'],
    });
  });

  it('keeps the read sets exactly on the four backend-admitted roles', () => {
    // These role sets feed the shell navigation too; widening them would hint
    // at screens the server refuses with 403.
    expect(PERMISSIONS.patient.read).toEqual(['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']);
    expect(PERMISSIONS.appointment.read).toEqual(['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']);
    expect(PERMISSIONS.admission.read).toEqual(['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']);
    expect(PERMISSIONS.emergencyVisit.read).toEqual(['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']);
    expect(PERMISSIONS.invoice.read).toEqual(['ADMIN', 'BILLING']);
  });
});
