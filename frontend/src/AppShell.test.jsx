import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AppShell from './AppShell.jsx';
import { loadSession, saveSession } from './auth.js';

const DASHBOARD_STATS = { patients: 12, appointmentsToday: 4 };

const PATIENTS_PAGE = [
  {
    id: '55555555-5555-4555-8555-555555555555',
    medicalRecordNumber: 'MRN-2001',
    fullName: 'Synthetic Patient',
    dateOfBirth: '1980-01-01',
    sex: 'female',
    phone: '',
    email: '',
    nationalId: '',
    address: '',
    active: true,
  },
];

const STAFF_DIRECTORY = [
  {
    id: '66666666-6666-4666-8666-666666666666',
    employeeCode: 'EMP-3001',
    fullName: 'Synthetic Professional',
    profession: 'Cardiologist',
    licenseNumber: 'LIC-3001',
    department: 'Cardiology',
  },
];

const APPOINTMENTS_PAGE = [
  {
    id: '77777777-7777-4777-8777-777777777777',
    patientId: '55555555-5555-4555-8555-555555555555',
    professionalId: '66666666-6666-4666-8666-666666666666',
    scheduledAt: '2026-03-01T09:30',
    type: 'Consultation',
    status: 'scheduled',
  },
];

// Admissions list over GET /api/admissions (docs/plan2.md Task 2 DTO
// contract): id/patientId/admittedAt/dischargedAt/reason/status, no
// persistence metadata.
const ADMISSIONS_PAGE = [
  {
    id: '88888888-8888-4888-8888-888888888801',
    patientId: '55555555-5555-4555-8555-555555555555',
    admittedAt: '2031-01-01T08:15:30',
    dischargedAt: null,
    reason: 'Synthetic observation stay',
    status: 'ADMITTED',
  },
  {
    id: '88888888-8888-4888-8888-888888888802',
    patientId: 'raw unresolved reference',
    admittedAt: '2031-02-01T10:00',
    dischargedAt: '2031-02-05T09:30',
    reason: 'Synthetic completed stay',
    status: 'DISCHARGED',
  },
];

// Emergency-visits list over GET /api/emergency-visits (docs/plan2.md Task 3
// DTO contract): id/patientId/arrivalAt/triageLevel/chiefComplaint/status —
// no persistence metadata. The triage label is a neutral demo value.
const EMERGENCY_VISITS_PAGE = [
  {
    id: '88888888-8888-4888-8888-888888888901',
    patientId: '55555555-5555-4555-8555-555555555555',
    arrivalAt: '2031-01-01T09:15',
    triageLevel: '3',
    chiefComplaint: 'Synthetic demo complaint',
    status: 'WAITING',
  },
  {
    id: '88888888-8888-4888-8888-888888888902',
    patientId: 'raw unresolved reference',
    arrivalAt: '2031-01-02T11:30',
    triageLevel: '5',
    chiefComplaint: 'Synthetic closed visit',
    status: 'CLOSED',
  },
];

// Invoices list over GET /api/invoices (docs/plan2.md Task 4 DTO contract):
// id/patientId/invoiceNumber/amount/currency/status — no persistence
// metadata. The whole family is a FINANCIAL SIMULATION: no real payments.
const INVOICES_PAGE = [
  {
    id: '88888888-8888-4888-8888-888888888701',
    patientId: '55555555-5555-4555-8555-555555555555',
    invoiceNumber: 'INV-2026-0001',
    amount: '150.00',
    currency: 'USD',
    status: 'DRAFT',
  },
  {
    id: '88888888-8888-4888-8888-888888888702',
    patientId: 'raw unresolved reference',
    invoiceNumber: 'INV-2026-0002',
    amount: '42.50',
    currency: 'EUR',
    status: 'PAID',
  },
];

// AuditEvent contract over GET /api/audit (ADMIN-only): evidence fields plus
// internal metadata the screen must never render.
const AUDIT_EVENTS = [
  {
    id: '88888888-8888-4888-8888-888888888888',
    createdAt: '2026-03-02T10:00:00Z',
    updatedAt: '2026-03-02T10:00:00Z',
    version: 0,
    actor: 'admin',
    action: 'CREATE',
    resourceType: 'Appointment',
    resourceId: '77777777-7777-4777-8777-777777777777',
    details: 'created',
    occurredAt: '2026-03-02T10:00:00Z',
  },
];

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

// Server-issued assignment views (docs/plan3.md Task 3 response shape).
const ORG_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const EAST_BRANCH_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const WEST_BRANCH_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

// Server-owned organization view (GET /api/organization response shape): the
// ACTIVE branches in server order. The selector's ORGANIZATION targets and
// the honest whoami branch label come from this allowlist and nowhere else.
const ORGANIZATION_VIEW = {
  id: ORG_ID,
  code: 'MHG',
  name: 'Main Hospital Group',
  activeBranches: [
    { id: EAST_BRANCH_ID, organizationId: ORG_ID, code: 'EAST', name: 'East Clinic', locationLabel: '1 East Way', active: true },
    { id: WEST_BRANCH_ID, organizationId: ORG_ID, code: 'WEST', name: 'West Clinic', locationLabel: '9 West Way', active: true },
  ],
};
const ASSIGNMENTS = {
  DOCTOR: {
    id: '11111111-1111-4111-8111-111111111111',
    role: 'DOCTOR',
    scope: 'BRANCH',
    organizationId: ORG_ID,
    organizationLabel: 'Main Hospital Group',
    branchId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    branchLabel: 'East Clinic',
    departmentId: null,
    departmentLabel: null,
    enabled: true,
  },
  NURSE: {
    id: '22222222-2222-4222-8222-222222222222',
    role: 'NURSE',
    scope: 'DEPARTMENT',
    organizationId: ORG_ID,
    organizationLabel: 'Main Hospital Group',
    branchId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    branchLabel: 'West Clinic',
    departmentId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
    departmentLabel: 'Outpatient Clinic',
    enabled: true,
  },
  ADMIN: {
    id: '33333333-3333-4333-8333-333333333333',
    role: 'ADMIN',
    scope: 'ORGANIZATION',
    organizationId: ORG_ID,
    organizationLabel: 'Main Hospital Group',
    branchId: null,
    branchLabel: null,
    departmentId: null,
    departmentLabel: null,
    enabled: true,
  },
  BILLING: {
    id: '44444444-4444-4444-8444-444444444444',
    role: 'BILLING',
    scope: 'BRANCH',
    organizationId: ORG_ID,
    organizationLabel: 'Main Hospital Group',
    branchId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    branchLabel: 'East Clinic',
    departmentId: null,
    departmentLabel: null,
    enabled: true,
  },
  RECEPTIONIST: {
    id: '55555555-5555-4555-8555-555555555555',
    role: 'RECEPTIONIST',
    scope: 'BRANCH',
    organizationId: ORG_ID,
    organizationLabel: 'Main Hospital Group',
    branchId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    branchLabel: 'West Clinic',
    departmentId: null,
    departmentLabel: null,
    enabled: true,
  },
};

// Complete server-issued session: token, single selected role, the
// assignment list, and the selected acting context (Task 3 response shape).
function fullSession(role, extraAssignments = []) {
  const assignment = ASSIGNMENTS[role];
  return {
    token: 'synthetic-token',
    username: 'testuser',
    roles: [role],
    assignments: [assignment, ...extraAssignments],
    actingContext: {
      username: 'testuser',
      assignmentId: assignment.id,
      role: assignment.role,
      scope: assignment.scope,
      organizationId: assignment.organizationId,
      branchId: assignment.branchId ?? 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      departmentId: assignment.departmentId ?? null,
    },
  };
}

function stubBackendApi(state = {}) {
  return vi.fn((path, options = {}) => {
    if (path === '/api/dashboard') return Promise.resolve(jsonResponse(DASHBOARD_STATS));
    if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS_PAGE));
    if (path === '/api/appointments') return Promise.resolve(jsonResponse(APPOINTMENTS_PAGE));
    if (path === '/api/admissions') return Promise.resolve(jsonResponse(ADMISSIONS_PAGE));
    if (path === '/api/emergency-visits') return Promise.resolve(jsonResponse(EMERGENCY_VISITS_PAGE));
    if (path === '/api/invoices') return Promise.resolve(jsonResponse(INVOICES_PAGE));
    if (path === '/api/staff') return Promise.resolve(jsonResponse(STAFF_DIRECTORY));
    if (path === '/api/audit') return Promise.resolve(jsonResponse(AUDIT_EVENTS));
    if (path === '/api/organization') {
      state.organizationStatus = state.organizationStatus ?? 200;
      state.organizationBody = state.organizationBody ?? ORGANIZATION_VIEW;
      return Promise.resolve(jsonResponse(state.organizationBody, state.organizationStatus));
    }
    if (path === '/api/auth/context') {
      state.contextCalls = state.contextCalls ?? [];
      state.contextCalls.push({ body: JSON.parse(options.body), auth: options.headers.Authorization });
      return Promise.resolve(jsonResponse(state.contextBody ?? { error: 'not found' }, state.contextStatus ?? 404));
    }
    return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
  });
}

function renderShell(roles, extraAssignments = [], sessionOverrides = {}) {
  // Existing callers pass a single-role array; newer ones a bare role.
  const role = Array.isArray(roles) ? roles[0] : roles;
  const onSessionExpired = vi.fn();
  const view = render(
    <AppShell
      session={{ ...fullSession(role, extraAssignments), ...sessionOverrides }}
      onLogout={vi.fn()}
      onSessionExpired={onSessionExpired}
    />
  );
  return { onSessionExpired, view };
}

async function waitForDashboardStats() {
  // Task 5 renders the human label 'Patients', which also names a nav
  // button; the selector isolates the dashboard stat-card label.
  await screen.findByText('Patients', { selector: 'small' });
  await waitFor(() => expect(screen.getByText('12')).toBeInTheDocument());
}

function navigationItems() {
  const nav = screen.getByRole('navigation', { name: 'Screens permitted for your roles' });
  return within(nav).getAllByRole('button').map((button) => button.textContent);
}

describe('AppShell', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = stubBackendApi();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('lists only the destinations the session roles permit and never offers unimplemented modules', async () => {
    renderShell(['DOCTOR']);

    // plan2.md Tasks 2-3: Admissions and Emergency Visits join the four
    // clinical-administrative destinations (server family rules on
    // /api/admissions/** and /api/emergency-visits/**).
    expect(navigationItems()).toEqual(
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits'],
    );
    for (const unimplemented of ['Laboratory', 'Pharmacy', 'Beds']) {
      expect(screen.queryByRole('button', { name: unimplemented })).not.toBeInTheDocument();
    }
    // The audit evidence screen is implemented but ADMIN-only (plan1.md
    // Task 10): DOCTOR never sees the destination.
    expect(screen.queryByRole('button', { name: 'Audit' })).not.toBeInTheDocument();
    await waitForDashboardStats();
  });

  it('hides clinical destinations from BILLING and offers it the invoices family only', async () => {
    renderShell(['BILLING']);

    // plan2.md Task 4: BILLING is the second invoice role server-side, so
    // the shell offers exactly Dashboard + Invoices to it — nothing else.
    expect(navigationItems()).toEqual(['Dashboard', 'Invoices']);
    for (const denied of ['Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Audit']) {
      expect(screen.queryByRole('button', { name: denied })).not.toBeInTheDocument();
    }
    await waitForDashboardStats();
  });

  it('opens the invoices screen for a billing role and loads its list once', async () => {
    const user = userEvent.setup();
    renderShell(['BILLING']);
    await waitForDashboardStats();

    await user.click(screen.getByRole('button', { name: 'Invoices' }));

    expect(screen.getByRole('button', { name: 'Invoices' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Invoices' })).toBeInTheDocument();
    const invoicesScreen = screen.getByRole('region', { name: 'Invoices screen' });
    const table = await within(invoicesScreen).findByRole('table', { name: 'Registered invoices' });
    expect(within(table).getAllByRole('row')).toHaveLength(3); // header + two records
    // Resolved patient name, status badges, and honest placeholders.
    expect(within(table).getByText('Synthetic Patient')).toBeInTheDocument();
    expect(within(table).getByText('DRAFT')).toBeInTheDocument();
    expect(within(table).getByText('PAID')).toBeInTheDocument();
    expect(invoicesScreen).toHaveTextContent('Unknown record');
    expect(invoicesScreen).not.toHaveTextContent('raw unresolved reference');
    // The financial-simulation boundary is demonstrably labeled in the UI.
    expect(invoicesScreen).toHaveTextContent(/financial simulation/i);
    expect(invoicesScreen).toHaveTextContent(/no real payments/i);
    // A billing role may create invoices.
    expect(
      within(invoicesScreen).getByRole('button', { name: 'Create invoice' })
    ).toBeInTheDocument();

    const invoiceCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/invoices');
    expect(invoiceCalls).toHaveLength(1);
    expect(invoiceCalls[0][1].method).toBe('GET');
    expect(invoiceCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
  });

  it('defaults to the dashboard on a fresh mount, so a refresh falls back to it', async () => {
    renderShell(['DOCTOR']);

    expect(screen.getByRole('button', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Operations Dashboard' })).toBeInTheDocument();
    await waitForDashboardStats();
  });

  it('switches among dashboard, patients, and appointments selections with honest screen boundaries', async () => {
    const user = userEvent.setup();
    renderShell(['DOCTOR']);
    await waitForDashboardStats();
    const dashboardCalls = fetchMock.mock.calls.length;

    await user.click(screen.getByRole('button', { name: 'Appointments' }));

    expect(screen.getByRole('button', { name: 'Appointments' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Dashboard' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('heading', { name: 'Appointments' })).toBeInTheDocument();
    const appointmentsScreen = screen.getByRole('region', { name: 'Appointments screen' });
    await within(appointmentsScreen).findByText('Synthetic Patient');
    expect(
      within(appointmentsScreen).getByRole('table', { name: 'Scheduled appointments' })
    ).toBeInTheDocument();
    expect(appointmentsScreen).toHaveTextContent('Synthetic Professional — Cardiologist (Cardiology)');
    expect(appointmentsScreen).toHaveTextContent('2026-03-01T09:30');
    expect(appointmentsScreen).toHaveTextContent('scheduled');
    // DOCTOR may view the list but never sees the scheduling action (UI
    // convenience only; the backend stays authoritative).
    expect(
      within(appointmentsScreen).queryByRole('button', { name: 'Schedule appointment' })
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Patients', { selector: 'small' })).not.toBeInTheDocument();
    // The real screen fetches its own data: appointments list, professionals,
    // and patients for name resolution.
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/appointments')).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/staff')).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(dashboardCalls + 3);

    await user.click(screen.getByRole('button', { name: 'Patients' }));

    expect(screen.getByRole('button', { name: 'Patients' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Patients' })).toBeInTheDocument();
    const patientsScreen = screen.getByRole('region', { name: 'Patients screen' });
    expect(
      within(patientsScreen).getByRole('searchbox', { name: 'Search patients' })
    ).toBeInTheDocument();
    await within(patientsScreen).findByText('Synthetic Patient');
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/patients')).toHaveLength(2);
    expect(fetchMock).toHaveBeenCalledTimes(dashboardCalls + 4);

    await user.click(screen.getByRole('button', { name: 'Dashboard' }));

    expect(screen.getByRole('button', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByRole('region', { name: 'Patients screen' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Operations Dashboard' })).toBeInTheDocument();
  });

  it('opens the admissions screen for a clinical-administrative role and loads its list once', async () => {
    const user = userEvent.setup();
    renderShell(['RECEPTIONIST']);
    await waitForDashboardStats();

    expect(navigationItems()).toEqual(
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits'],
    );
    await user.click(screen.getByRole('button', { name: 'Admissions' }));

    expect(screen.getByRole('button', { name: 'Admissions' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Admissions' })).toBeInTheDocument();
    const admissionsScreen = screen.getByRole('region', { name: 'Admissions screen' });
    const table = await within(admissionsScreen).findByRole('table', { name: 'Registered admissions' });
    expect(within(table).getAllByRole('row')).toHaveLength(3); // header + two records
    expect(within(admissionsScreen).getAllByText('ADMITTED').length).toBeGreaterThan(0);
    expect(within(admissionsScreen).getAllByText('DISCHARGED').length).toBeGreaterThan(0);
    // Unresolved references render honestly; raw values never surface.
    expect(admissionsScreen).toHaveTextContent('Unknown record');
    expect(admissionsScreen).not.toHaveTextContent('raw unresolved reference');
    // All four clinical-administrative roles may register and discharge.
    expect(
      within(admissionsScreen).getByRole('button', { name: 'Register admission' })
    ).toBeInTheDocument();

    const admissionCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/admissions');
    expect(admissionCalls).toHaveLength(1);
    expect(admissionCalls[0][1].method).toBe('GET');
    expect(admissionCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
  });

  it('opens the emergency-visits screen for a clinical-administrative role and loads its list once', async () => {
    const user = userEvent.setup();
    renderShell(['NURSE']);
    await waitForDashboardStats();

    await user.click(screen.getByRole('button', { name: 'Emergency Visits' }));

    expect(screen.getByRole('button', { name: 'Emergency Visits' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Emergency Visits' })).toBeInTheDocument();
    const emergencyScreen = screen.getByRole('region', { name: 'Emergency Visits screen' });
    const table = await within(emergencyScreen).findByRole('table', { name: 'Registered emergency visits' });
    expect(within(table).getAllByRole('row')).toHaveLength(3); // header + two records
    // Resolved patient name, neutral triage demo labels, and status badges.
    expect(within(table).getByText('Synthetic Patient')).toBeInTheDocument();
    expect(within(table).getByText('WAITING')).toBeInTheDocument();
    expect(within(table).getByText('CLOSED')).toBeInTheDocument();
    // Unresolved references render honestly; raw values never surface.
    expect(emergencyScreen).toHaveTextContent('Unknown record');
    expect(emergencyScreen).not.toHaveTextContent('raw unresolved reference');
    // The non-clinical boundary is demonstrably labeled in the UI.
    expect(emergencyScreen).toHaveTextContent(/no clinical meaning/i);
    // A clinical-administrative role may register visits.
    expect(
      within(emergencyScreen).getByRole('button', { name: 'Register visit' })
    ).toBeInTheDocument();

    const visitCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/emergency-visits');
    expect(visitCalls).toHaveLength(1);
    expect(visitCalls[0][1].method).toBe('GET');
    expect(visitCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
  });

  it('activates a destination from the keyboard', async () => {
    const user = userEvent.setup();
    renderShell(['DOCTOR']);
    await waitForDashboardStats();

    screen.getByRole('button', { name: 'Appointments' }).focus();
    await user.keyboard('{Enter}');

    expect(screen.getByRole('button', { name: 'Appointments' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Appointments' })).toBeInTheDocument();
  });

  it('offers the audit evidence screen to ADMIN only and loads real events from /api/audit', async () => {
    const user = userEvent.setup();
    renderShell(['ADMIN']);

    expect(navigationItems()).toEqual(
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Invoices', 'Audit'],
    );
    await waitForDashboardStats();

    await user.click(screen.getByRole('button', { name: 'Audit' }));

    expect(screen.getByRole('button', { name: 'Audit' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Audit Evidence' })).toBeInTheDocument();
    const auditScreen = screen.getByRole('region', { name: 'Audit screen' });
    await within(auditScreen).findByText('CREATE');
    expect(
      within(auditScreen).getByRole('table', { name: 'Audit events' })
    ).toBeInTheDocument();

    // The read uses the session bearer token and never renders it or any
    // other credential on screen.
    const auditCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/audit');
    expect(auditCalls).toHaveLength(1);
    expect(auditCalls[0][1].method).toBe('GET');
    expect(auditCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
    expect(auditScreen).not.toHaveTextContent('synthetic-token');
  });

  it('reaches logout from the shell', async () => {
    const user = userEvent.setup();
    const onLogout = vi.fn();
    render(
      <AppShell
        session={fullSession('DOCTOR')}
        onLogout={onLogout}
        onSessionExpired={vi.fn()}
      />
    );
    await waitForDashboardStats();

    await user.click(screen.getByRole('button', { name: 'Log out' }));

    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('always displays the username, acting role, branch, and optional department from the session', () => {
    renderShell('DOCTOR');

    expect(screen.getByText('testuser')).toBeInTheDocument();
    expect(screen.getByText('DOCTOR')).toBeInTheDocument();
    expect(screen.getByText('East Clinic')).toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
  });

  it('shows the department label only for a department-scoped acting context', () => {
    renderShell('NURSE');

    expect(screen.getByText('NURSE')).toBeInTheDocument();
    expect(screen.getByText('West Clinic')).toBeInTheDocument();
    expect(screen.getByText('Outpatient Clinic')).toBeInTheDocument();
  });

  it('names the server-issued branch of a branch-bound ORGANIZATION acting context, with a neutral loading fallback', async () => {
    renderShell('ADMIN');

    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    // While the active-branch list loads: neutral — never "organization-wide"
    // for a token the server bound to one concrete branch.
    expect(screen.getByText('Main Hospital Group — loading branch…')).toBeInTheDocument();
    // Once loaded, the acting branch is named from the server allowlist.
    expect(await screen.findByText('Main Hospital Group — East Clinic')).toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
    // The branch list came from GET /api/organization with the session token.
    const orgCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/organization');
    expect(orgCalls).toHaveLength(1);
    expect(orgCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
  });

  it('shows a neutral branch-id fallback when the acting branch is absent from the active list', async () => {
    const state = { organizationBody: { ...ORGANIZATION_VIEW, activeBranches: [] } };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);
    renderShell('ADMIN');

    expect(await screen.findByText(`Main Hospital Group — branch ${EAST_BRANCH_ID}`)).toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
  });

  it('keeps the prior context and shows accessible text when the branch list cannot be loaded', async () => {
    const state = { organizationStatus: 403, organizationBody: { error: 'Forbidden' } };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);
    const { onSessionExpired } = renderShell('ADMIN');

    // A non-ADMIN token cannot read GET /api/organization: the refusal is
    // surfaced as text, the session/context/selection stay untouched, and no
    // organization-wide claim is invented for the branch-bound token.
    expect(await screen.findByRole('alert')).toHaveTextContent(/branch options could not be loaded/i);
    expect(screen.getByText(`Main Hospital Group — branch ${EAST_BRANCH_ID}`)).toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('offers the branch selector with the server-issued (assignment, branch) pairs and a keyboard-operable native select', async () => {
    const user = userEvent.setup();
    renderShell('DOCTOR', [ASSIGNMENTS.ADMIN]);
    await waitForDashboardStats();

    const select = screen.getByRole('combobox', { name: 'Acting context' });
    // The acting pair (DOCTOR fixed on East) is the current selection.
    expect(select).toHaveValue(`${ASSIGNMENTS.DOCTOR.id}|${EAST_BRANCH_ID}`);
    await waitFor(() => expect(select.querySelectorAll('option')).toHaveLength(3));
    const options = [...select.querySelectorAll('option')];
    // The fixed assignment offers exactly its branch; the ORGANIZATION
    // assignment offers each server-issued active branch.
    expect(options.map((option) => option.value)).toEqual([
      `${ASSIGNMENTS.DOCTOR.id}|${EAST_BRANCH_ID}`,
      `${ASSIGNMENTS.ADMIN.id}|${EAST_BRANCH_ID}`,
      `${ASSIGNMENTS.ADMIN.id}|${WEST_BRANCH_ID}`,
    ]);
    expect(options[0]).toHaveTextContent('DOCTOR — East Clinic · Main Hospital Group');
    expect(options[1]).toHaveTextContent('ADMIN — East Clinic · Main Hospital Group');
    expect(options[2]).toHaveTextContent('ADMIN — West Clinic · Main Hospital Group');
    expect(screen.getByLabelText('Acting context')).toBe(select);

    // Keyboard operability: focus the labeled native control and switch.
    select.focus();
    expect(select).toHaveFocus();
    await user.selectOptions(select, `${ASSIGNMENTS.ADMIN.id}|${EAST_BRANCH_ID}`);

    await waitFor(() => expect(fetchMock.mock.calls.some(([path]) => path === '/api/auth/context')).toBe(true));
    const contextCall = fetchMock.mock.calls.find(([path]) => path === '/api/auth/context');
    expect(contextCall[1].method).toBe('POST');
    expect(contextCall[1].headers.Authorization).toBe('Bearer synthetic-token');
    // The ORGANIZATION target names the assignment AND the chosen branch.
    expect(JSON.parse(contextCall[1].body)).toEqual({
      assignmentId: ASSIGNMENTS.ADMIN.id,
      branchId: EAST_BRANCH_ID,
    });
  });

  it('atomically replaces the session on a successful switch and reloads the branch-scoped view with the new token', async () => {
    const user = userEvent.setup();
    const state = {
      contextStatus: 200,
      contextBody: {
        accessToken: 'switched-context-token',
        tokenType: 'Bearer',
        username: 'testuser',
        roles: ['ADMIN'],
        assignments: [ASSIGNMENTS.DOCTOR, ASSIGNMENTS.ADMIN],
        actingContext: {
          username: 'testuser',
          assignmentId: ASSIGNMENTS.ADMIN.id,
          role: 'ADMIN',
          scope: 'ORGANIZATION',
          organizationId: ORG_ID,
          branchId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
          departmentId: null,
        },
      },
    };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);

    sessionStorage.clear();
    const onSessionExpired = vi.fn();
    let view;
    // The app-level handler: atomic storage replacement + state swap.
    const switchAndApply = (nextSession) => {
      saveSession(nextSession);
      view.rerender(
        <AppShell
          session={nextSession}
          onLogout={vi.fn()}
          onSessionExpired={onSessionExpired}
          onContextSwitch={switchAndApply}
        />
      );
    };
    view = render(
      <AppShell
        session={fullSession('DOCTOR', [ASSIGNMENTS.ADMIN])}
        onLogout={vi.fn()}
        onSessionExpired={onSessionExpired}
        onContextSwitch={switchAndApply}
      />
    );
    await waitForDashboardStats();

    // Open the branch-scoped patients view under the first context.
    await user.click(screen.getByRole('button', { name: 'Patients' }));
    await screen.findByRole('list', { name: 'Patient results' });
    const patientsCallsBefore = fetchMock.mock.calls.filter(([path]) => path === '/api/patients').length;

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Acting context' }).querySelectorAll('option')).toHaveLength(3));
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ASSIGNMENTS.ADMIN.id}|${EAST_BRANCH_ID}`,
    );

    // Whoami reflects the new acting context immediately, naming the branch
    // the new token is bound to — never an organization-wide claim.
    await waitFor(() => expect(screen.getByText('Main Hospital Group — East Clinic')).toBeInTheDocument());
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    // The selected patients view reloaded from the server under the new
    // context-bound token — a fresh fetch owned by the same screen effect.
    await screen.findAllByRole('list', { name: 'Patient results' });
    const patientCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/patients');
    expect(patientCalls.length).toBeGreaterThan(patientsCallsBefore);
    expect(patientCalls[patientCalls.length - 1][1].headers.Authorization).toBe('Bearer switched-context-token');
    // The switch request named both the assignment and the chosen branch.
    const contextCall = fetchMock.mock.calls.find(([path]) => path === '/api/auth/context');
    expect(JSON.parse(contextCall[1].body)).toEqual({
      assignmentId: ASSIGNMENTS.ADMIN.id,
      branchId: EAST_BRANCH_ID,
    });
    // Storage holds exactly the complete switched session.
    expect(JSON.parse(sessionStorage.getItem('medicore.session')).token).toBe('switched-context-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('switches one organization assignment from branch A to branch B and reloads the view under the new token', async () => {
    const user = userEvent.setup();
    const state = {
      contextStatus: 200,
      contextBody: {
        accessToken: 'west-context-token',
        tokenType: 'Bearer',
        username: 'testuser',
        roles: ['ADMIN'],
        assignments: [ASSIGNMENTS.ADMIN],
        actingContext: {
          username: 'testuser',
          assignmentId: ASSIGNMENTS.ADMIN.id,
          role: 'ADMIN',
          scope: 'ORGANIZATION',
          organizationId: ORG_ID,
          branchId: WEST_BRANCH_ID,
          departmentId: null,
        },
      },
    };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);

    sessionStorage.clear();
    const onSessionExpired = vi.fn();
    let view;
    const switchAndApply = (nextSession) => {
      saveSession(nextSession);
      view.rerender(
        <AppShell
          session={nextSession}
          onLogout={vi.fn()}
          onSessionExpired={onSessionExpired}
          onContextSwitch={switchAndApply}
        />
      );
    };
    view = render(
      <AppShell
        session={fullSession('ADMIN')}
        onLogout={vi.fn()}
        onSessionExpired={onSessionExpired}
        onContextSwitch={switchAndApply}
      />
    );
    await waitForDashboardStats();
    await waitFor(() => expect(screen.getByText('Main Hospital Group — East Clinic')).toBeInTheDocument());

    // Same assignment, different branch: the pair target carries the change.
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ASSIGNMENTS.ADMIN.id}|${WEST_BRANCH_ID}`,
    );

    // Whoami and storage now reflect the West-bound context.
    await waitFor(() => expect(screen.getByText('Main Hospital Group — West Clinic')).toBeInTheDocument());
    const contextCall = fetchMock.mock.calls.find(([path]) => path === '/api/auth/context');
    expect(JSON.parse(contextCall[1].body)).toEqual({
      assignmentId: ASSIGNMENTS.ADMIN.id,
      branchId: WEST_BRANCH_ID,
    });
    expect(JSON.parse(sessionStorage.getItem('medicore.session')).token).toBe('west-context-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
    // The East branch line is gone — no stale claim survives the switch.
    expect(screen.queryByText('Main Hospital Group — East Clinic')).not.toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
  });

  it('keeps the prior login, token, and context and shows an in-place denial on a 403 switch', async () => {
    const user = userEvent.setup();
    const state = { contextStatus: 403, contextBody: { error: 'Forbidden' } };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);
    // A sentinel session marks the storage boundary: the refused switch
    // must not partially update storage.
    sessionStorage.clear();
    const sentinel = { token: 'sentinel-token', username: 'sentinel', roles: ['DOCTOR'] };
    saveSession(sentinel);
    const onSessionExpired = vi.fn();
    render(
      <AppShell
        session={fullSession('DOCTOR', [ASSIGNMENTS.ADMIN])}
        onLogout={vi.fn()}
        onSessionExpired={onSessionExpired}
      />
    );
    await waitForDashboardStats();

    await user.click(screen.getByRole('button', { name: 'Patients' }));
    await screen.findByRole('list', { name: 'Patient results' });
    const patientCallsBefore = fetchMock.mock.calls.filter(([path]) => path === '/api/patients').length;

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Acting context' }).querySelectorAll('option')).toHaveLength(3));
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ASSIGNMENTS.ADMIN.id}|${EAST_BRANCH_ID}`,
    );

    // Understandable in-place denial — text, not color-only.
    expect(await screen.findByRole('alert')).toHaveTextContent(/refused this context switch/i);
    // Prior session fully preserved: identity, role, branch, and selection.
    expect(screen.getByText('DOCTOR')).toBeInTheDocument();
    expect(screen.getByText('East Clinic')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Acting context' })).toHaveValue(
      `${ASSIGNMENTS.DOCTOR.id}|${EAST_BRANCH_ID}`,
    );
    // No expiry, and the branch-scoped view was not refetched against the
    // refused context: no partial storage or UI update.
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/patients')).toHaveLength(patientCallsBefore);
    // Storage still holds exactly the sentinel — no partial update.
    expect(JSON.parse(sessionStorage.getItem('medicore.session'))).toEqual(sentinel);
  });

  it('clears the session on a 401 context switch through the expiry handler', async () => {
    const user = userEvent.setup();
    const state = { contextStatus: 401, contextBody: { error: 'expired' } };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);
    const onSessionExpired = vi.fn();
    render(
      <AppShell
        session={fullSession('DOCTOR', [ASSIGNMENTS.ADMIN])}
        onLogout={vi.fn()}
        onSessionExpired={onSessionExpired}
      />
    );
    await waitForDashboardStats();
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Acting context' }).querySelectorAll('option')).toHaveLength(3));

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ASSIGNMENTS.ADMIN.id}|${WEST_BRANCH_ID}`,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  describe('session reload (auth storage boundary, plan3.md Task 5 contract §5)', () => {
    it('restores a valid complete server-issued session through real sessionStorage', () => {
      sessionStorage.clear();
      const complete = fullSession('DOCTOR', [ASSIGNMENTS.ADMIN]);

      saveSession(complete);

      expect(loadSession()).toEqual(complete);
    });

    it('rejects a reload whose acting assignment is missing from the stored list', () => {
      sessionStorage.clear();
      const orphaned = fullSession('DOCTOR', [ASSIGNMENTS.ADMIN]);
      orphaned.assignments = orphaned.assignments.filter((a) => a.id !== orphaned.actingContext.assignmentId);

      saveSession(orphaned);

      expect(loadSession()).toBeNull();
    });

    it('rejects stored sessions whose acting context contradicts the selected assignment', () => {
      const contradictions = [
        (session) => { session.actingContext.role = 'NURSE'; },
        (session) => { session.actingContext.scope = 'ORGANIZATION'; },
        (session) => { session.actingContext.organizationId = 'other-organization'; },
        (session) => { session.actingContext.branchId = 'other-branch'; },
        (session) => { session.actingContext.departmentId = 'other-department'; },
        (session) => { session.actingContext.username = 'other-user'; },
        (session) => { session.roles = ['NURSE']; },
        (session) => { session.assignments[0].enabled = false; },
      ];

      for (const contradict of contradictions) {
        sessionStorage.clear();
        const session = JSON.parse(JSON.stringify(fullSession('DOCTOR')));
        contradict(session);
        saveSession(session);
        expect(loadSession()).toBeNull();
      }
    });

    it('never treats a stored branch id as authority without the matching context-bound token', () => {
      sessionStorage.clear();
      const tokenless = fullSession('DOCTOR', [ASSIGNMENTS.ADMIN]);
      tokenless.token = '';

      saveSession(tokenless);

      expect(loadSession()).toBeNull();
    });

    it('rejects a reload with a malformed acting context even when a token exists', () => {
      sessionStorage.clear();
      const incomplete = fullSession('DOCTOR');
      incomplete.actingContext = { branchId: incomplete.actingContext.branchId };

      saveSession(incomplete);

      expect(loadSession()).toBeNull();
    });
  });
});
