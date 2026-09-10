import { describe, expect, it } from 'vitest';
import { canViewDestination, defaultDestination, permittedDestinations } from './navigation.js';

// docs/plan2.md Tasks 2-3: the shell gains the Admissions and Emergency
// Visits destinations for the four clinical-administrative roles that the
// server family rule admits on /api/admissions/** and
// /api/emergency-visits/** (ADMIN, DOCTOR, NURSE, RECEPTIONIST). The role
// sets come from the shared permission map in authorization.js; they mirror
// SecurityConfig and never authorize anything. These tests pin the pure
// navigation registry so a destination cannot silently widen or shrink its
// role surface.
const sessionFor = (...roles) => ({ token: 'synthetic-token', username: 'testuser', roles });

function destinationIds(roles) {
  // permittedDestinations takes the role array (session.roles), never the
  // session object — the shell calls it exactly this way.
  return permittedDestinations(sessionFor(...roles).roles).map((destination) => destination.id);
}

describe('navigation registry (plan1.md Task 9 + plan2.md Tasks 2-3)', () => {
  it('defaults to the dashboard destination', () => {
    expect(defaultDestination().id).toBe('dashboard');
  });

  it('offers ADMIN every destination including the audit evidence screen', () => {
    expect(destinationIds(['ADMIN'])).toEqual(
      ['dashboard', 'patients', 'appointments', 'admissions', 'emergency-visits', 'audit'],
    );
  });

  it('offers the four clinical-administrative roles the admissions and emergency-visits destinations but never audit', () => {
    for (const role of ['DOCTOR', 'NURSE', 'RECEPTIONIST']) {
      expect(destinationIds([role])).toEqual(
        ['dashboard', 'patients', 'appointments', 'admissions', 'emergency-visits'],
      );
    }
  });

  it('offers a role outside the clinical-front-desk set only the dashboard', () => {
    for (const role of ['BILLING', 'LAB_TECH', 'PHARMACIST', 'HR', 'STAFF']) {
      expect(destinationIds([role])).toEqual(['dashboard']);
    }
  });

  it('admissions is an implemented destination with its own label and heading', () => {
    const admissions = permittedDestinations(sessionFor('ADMIN').roles)
      .find((destination) => destination.id === 'admissions');
    expect(admissions).toBeDefined();
    expect(admissions.implemented).toBe(true);
    expect(admissions.label).toBe('Admissions');
    expect(admissions.heading).toBe('Admissions');
  });

  it('emergency-visits is an implemented destination with its own label and heading', () => {
    const emergency = permittedDestinations(sessionFor('ADMIN').roles)
      .find((destination) => destination.id === 'emergency-visits');
    expect(emergency).toBeDefined();
    expect(emergency.implemented).toBe(true);
    expect(emergency.label).toBe('Emergency Visits');
    expect(emergency.heading).toBe('Emergency Visits');
  });

  it('never offers any destination without roles; an unknown role still sees only the ANY dashboard', () => {
    // Missing or empty role lists deny every destination, so a malformed
    // session can never widen what the shell offers. A non-empty role set
    // outside the known roles still sees the dashboard only — 'ANY' means
    // any logged-in session, mirroring /api/dashboard/** authenticated().
    expect(permittedDestinations(undefined)).toEqual([]);
    expect(permittedDestinations([])).toEqual([]);
    expect(destinationIds(['UNKNOWN_ROLE'])).toEqual(['dashboard']);
  });

  it('answers canViewDestination from the destination role set only', () => {
    const admissions = permittedDestinations(sessionFor('ADMIN').roles)
      .find((destination) => destination.id === 'admissions');
    expect(canViewDestination(admissions, ['NURSE'])).toBe(true);
    expect(canViewDestination(admissions, ['BILLING'])).toBe(false);
    expect(canViewDestination(admissions, [])).toBe(false);
    expect(canViewDestination(admissions, undefined)).toBe(false);
    const emergency = permittedDestinations(sessionFor('ADMIN').roles)
      .find((destination) => destination.id === 'emergency-visits');
    expect(canViewDestination(emergency, ['RECEPTIONIST'])).toBe(true);
    expect(canViewDestination(emergency, ['BILLING'])).toBe(false);
    expect(canViewDestination(emergency, [])).toBe(false);
    expect(canViewDestination(emergency, undefined)).toBe(false);
  });
});
