import { describe, expect, it } from 'vitest';
import { canViewDestination, defaultDestination, permittedDestinations } from './navigation.js';

// docs/plan2.md Tasks 2-4: the shell gains the Admissions and Emergency
// Visits destinations for the four clinical-administrative roles that the
// server family rule admits on /api/admissions/** and
// /api/emergency-visits/** (ADMIN, DOCTOR, NURSE, RECEPTIONIST), and Task 4
// adds the Invoices destination for exactly ADMIN and BILLING, mirroring
// the server family rule on /api/invoices/** (a financial simulation — no
// real payments). The role sets come from the shared permission map in
// authorization.js; they mirror SecurityConfig and never authorize
// anything. These tests pin the pure navigation registry so a destination
// cannot silently widen or shrink its role surface.
const sessionFor = (...roles) => ({ token: 'synthetic-token', username: 'testuser', roles });

function destinationIds(roles) {
  // permittedDestinations takes the role array (session.roles), never the
  // session object — the shell calls it exactly this way.
  return permittedDestinations(sessionFor(...roles).roles).map((destination) => destination.id);
}

describe('navigation registry (plan1.md Task 9 + plan2.md Tasks 2-4)', () => {
  it('defaults to the dashboard destination', () => {
    expect(defaultDestination().id).toBe('dashboard');
  });

  it('offers ADMIN every destination including invoices and the audit evidence screen', () => {
    expect(destinationIds(['ADMIN'])).toEqual(
      ['dashboard', 'patients', 'appointments', 'admissions', 'emergency-visits', 'invoices', 'audit'],
    );
  });

  it('offers the four clinical-administrative roles the admissions and emergency-visits destinations but never audit', () => {
    for (const role of ['DOCTOR', 'NURSE', 'RECEPTIONIST']) {
      expect(destinationIds([role])).toEqual(
        ['dashboard', 'patients', 'appointments', 'admissions', 'emergency-visits'],
      );
    }
  });

  it('offers BILLING exactly the dashboard plus the invoices family destination', () => {
    // plan2.md Task 4: BILLING is the second invoice role server-side on
    // /api/invoices/**, so the shell offers it Invoices — and nothing else
    // beyond the dashboard.
    expect(destinationIds(['BILLING'])).toEqual(['dashboard', 'invoices']);
  });

  it('offers a role outside the admitted sets only the dashboard', () => {
    // plan2.md Task 6: every dashboard-only role is pinned, including
    // RADIOLOGY_TECH — the registry must not invent destinations the
    // server refuses with 403.
    for (const role of ['LAB_TECH', 'RADIOLOGY_TECH', 'PHARMACIST', 'HR', 'STAFF']) {
      expect(destinationIds([role])).toEqual(['dashboard']);
    }
  });

  it('offers multi-role sessions the union of their destination sets', () => {
    // Combining roles may add destinations but never widens past the
    // per-role sets: audit stays reachable through ADMIN only.
    expect(destinationIds(['DOCTOR', 'BILLING'])).toEqual(
      ['dashboard', 'patients', 'appointments', 'admissions', 'emergency-visits', 'invoices'],
    );
    expect(destinationIds(['LAB_TECH', 'BILLING'])).toEqual(['dashboard', 'invoices']);
    expect(destinationIds(['RECEPTIONIST', 'HR'])).toEqual(
      ['dashboard', 'patients', 'appointments', 'admissions', 'emergency-visits'],
    );
    expect(destinationIds(['ADMIN', 'LAB_TECH'])).toEqual(
      ['dashboard', 'patients', 'appointments', 'admissions', 'emergency-visits', 'invoices', 'audit'],
    );
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

  it('invoices is an implemented destination with its own label and heading', () => {
    const invoices = permittedDestinations(sessionFor('ADMIN').roles)
      .find((destination) => destination.id === 'invoices');
    expect(invoices).toBeDefined();
    expect(invoices.implemented).toBe(true);
    expect(invoices.label).toBe('Invoices');
    expect(invoices.heading).toBe('Invoices');
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
    const invoices = permittedDestinations(sessionFor('ADMIN').roles)
      .find((destination) => destination.id === 'invoices');
    expect(canViewDestination(invoices, ['ADMIN'])).toBe(true);
    expect(canViewDestination(invoices, ['BILLING'])).toBe(true);
    expect(canViewDestination(invoices, ['DOCTOR'])).toBe(false);
    expect(canViewDestination(invoices, ['RECEPTIONIST'])).toBe(false);
    expect(canViewDestination(invoices, [])).toBe(false);
    expect(canViewDestination(invoices, undefined)).toBe(false);
  });
});
