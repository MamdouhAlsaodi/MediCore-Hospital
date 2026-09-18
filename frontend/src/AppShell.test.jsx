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

// Beds list over GET /api/beds (docs/plan3.md Task 6 DTO contract):
// id/branchId/ward/room/bedNumber/occupancyStatus — no persistence metadata
// and no legacy patient reference anywhere. OCCUPIED is admission-owned.
const BEDS_PAGE = [
  {
    id: '99999999-9999-4999-8999-999999999801',
    branchId: '44444444-4444-4444-8444-444444444444',
    ward: 'Ward A',
    room: '101',
    bedNumber: 'A-01',
    occupancyStatus: 'AVAILABLE',
  },
  {
    id: '99999999-9999-4999-8999-999999999802',
    branchId: '44444444-4444-4444-8444-444444444444',
    ward: 'Ward A',
    room: '102',
    bedNumber: 'A-02',
    occupancyStatus: 'MAINTENANCE',
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

// Server-issued assignment views (docs/plan3.md Task 3 response shape,
// extended with the hospital coordinates of Phase 5 T051): fixed scopes
// (HOSPITAL/BRANCH/DEPARTMENT) own their hospital; an ORGANIZATION
// assignment has none — its acting hospital is branch-specific.
const ORG_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const HOSPITAL_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee01';
const EAST_BRANCH_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const WEST_BRANCH_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

// Server-derived network hierarchy (GET /api/network/hierarchy response
// shape, parsed by networkApi.js): the authorized slice in server order.
// The selector's ORGANIZATION/HOSPITAL targets and the whoami hospital and
// branch names come from this slice and nowhere else.
const HIERARCHY_VIEW = {
  organizationId: ORG_ID,
  organizationCode: 'MHG',
  organizationName: 'Main Hospital Group',
  hospitals: [
    {
      id: HOSPITAL_ID,
      code: 'DEMO-C',
      name: 'Demo Central Hospital',
      regionLabel: 'Central Region',
      timeZone: 'UTC',
      active: true,
      branches: [
        { id: EAST_BRANCH_ID, hospitalId: HOSPITAL_ID, code: 'EAST', name: 'East Clinic', timeZone: 'UTC', active: true },
        { id: WEST_BRANCH_ID, hospitalId: HOSPITAL_ID, code: 'WEST', name: 'West Clinic', timeZone: 'UTC', active: true },
      ],
    },
  ],
};

// Server-owned organization view (GET /api/organization response shape):
// kept only because the transport adapter remains tested; the shell no
// longer consumes it (Phase 5 T058 moved the selector onto the hierarchy).
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
    hospitalId: HOSPITAL_ID,
    hospitalLabel: 'Demo Central Hospital',
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
    hospitalId: HOSPITAL_ID,
    hospitalLabel: 'Demo Central Hospital',
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
    hospitalId: null,
    hospitalLabel: null,
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
    hospitalId: HOSPITAL_ID,
    hospitalLabel: 'Demo Central Hospital',
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
    hospitalId: HOSPITAL_ID,
    hospitalLabel: 'Demo Central Hospital',
    branchId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    branchLabel: 'West Clinic',
    departmentId: null,
    departmentLabel: null,
    enabled: true,
  },
};

// Complete server-issued session: token, single selected role, the
// assignment list, and the selected acting context (Task 3 response shape,
// Phase 5: the context is always bound to one concrete hospital — the
// facility of the branch every scope resolves to).
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
      hospitalId: assignment.hospitalId ?? HOSPITAL_ID,
      branchId: assignment.branchId ?? EAST_BRANCH_ID,
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
    if (path === '/api/beds') return Promise.resolve(jsonResponse(BEDS_PAGE));
    if (path === '/api/invoices') return Promise.resolve(jsonResponse(INVOICES_PAGE));
    if (path === '/api/staff') return Promise.resolve(jsonResponse(STAFF_DIRECTORY));
    if (path === '/api/audit') return Promise.resolve(jsonResponse(AUDIT_EVENTS));
    if (path === '/api/organization') {
      state.organizationStatus = state.organizationStatus ?? 200;
      state.organizationBody = state.organizationBody ?? ORGANIZATION_VIEW;
      return Promise.resolve(jsonResponse(state.organizationBody, state.organizationStatus));
    }
    if (path === '/api/network/hierarchy') {
      state.hierarchyCalls = state.hierarchyCalls ?? [];
      state.hierarchyCalls.push(options.headers.Authorization);
      state.hierarchyStatus = state.hierarchyStatus ?? 200;
      state.hierarchyBody = state.hierarchyBody ?? HIERARCHY_VIEW;
      return Promise.resolve(jsonResponse(state.hierarchyBody, state.hierarchyStatus));
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

    // plan2.md Tasks 2-3 and plan3.md Task 6: Admissions, Emergency
    // Visits, and Beds join the four clinical-administrative destinations
    // (server family rules on /api/admissions/**, /api/emergency-visits/**,
    // and /api/beds/**).
    expect(navigationItems()).toEqual(
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Beds'],
    );
    for (const unimplemented of ['Laboratory', 'Pharmacy']) {
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
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Beds'],
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

  it('moves keyboard focus to the selected screen heading on mount and after navigation', async () => {
    const user = userEvent.setup();
    renderShell(['DOCTOR']);
    await waitForDashboardStats();

    // On mount the shell places focus on the dashboard heading so keyboard
    // users start inside the selected screen (docs/plan3.md Task 13).
    const dashboardHeading = screen.getByRole('heading', { name: 'Operations Dashboard' });
    expect(dashboardHeading).toHaveAttribute('tabindex', '-1');
    expect(document.activeElement).toBe(dashboardHeading);

    await user.click(screen.getByRole('button', { name: 'Patients' }));

    const patientsHeading = screen.getByRole('heading', { name: 'Patients' });
    expect(patientsHeading).toHaveAttribute('tabindex', '-1');
    expect(document.activeElement).toBe(patientsHeading);
  });

  it('returns keyboard focus to the current navigation control after the focused element is removed', async () => {
    const user = userEvent.setup();
    renderShell(['DOCTOR']);
    await waitForDashboardStats();

    await user.click(screen.getByRole('button', { name: 'Patients' }));
    const patientList = await screen.findByRole('list', { name: 'Patient results' });
    // Clicking a patient row focuses the row control; selecting it replaces
    // the list with the detail view, removing the focused element from the
    // DOM. The shell must return focus to a predictable, visible location:
    // the current screen's navigation control (docs/plan3.md Task 13).
    await user.click(within(patientList).getByRole('button', { name: /Synthetic Patient/ }));

    await waitFor(() => expect(document.activeElement).toBe(
      screen.getByRole('button', { name: 'Patients' })
    ));
    expect(document.activeElement).toHaveAttribute('aria-current', 'page');
  });

  it('opens the beds screen for a clinical-administrative role and loads its list once', async () => {
    const user = userEvent.setup();
    renderShell(['NURSE']);
    await waitForDashboardStats();

    expect(navigationItems()).toEqual(
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Beds'],
    );
    await user.click(screen.getByRole('button', { name: 'Beds' }));

    expect(screen.getByRole('button', { name: 'Beds' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Beds' })).toBeInTheDocument();
    const bedsScreen = screen.getByRole('region', { name: 'Beds screen' });
    const table = await within(bedsScreen).findByRole('table', { name: 'Registered beds' });
    expect(within(table).getAllByRole('row')).toHaveLength(3); // header + two records
    // Status badges render and every legal transition button exists for an
    // actionable role.
    expect(within(table).getByText('AVAILABLE')).toBeInTheDocument();
    expect(within(table).getByText('MAINTENANCE')).toBeInTheDocument();
    expect(
      within(bedsScreen).getByRole('button', { name: 'Add bed' })
    ).toBeInTheDocument();
    expect(
      within(bedsScreen).getByRole('button', { name: 'Start maintenance' })
    ).toBeInTheDocument();
    // The branch-scoping boundary is demonstrably labeled in the UI.
    expect(bedsScreen).toHaveTextContent(/branch you are acting on/i);

    const bedCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/beds');
    expect(bedCalls).toHaveLength(1);
    expect(bedCalls[0][1].method).toBe('GET');
    expect(bedCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
  });

  it('offers the audit evidence screen to ADMIN only and loads real events from /api/audit', async () => {
    const user = userEvent.setup();
    renderShell(['ADMIN']);

    expect(navigationItems()).toEqual(
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Beds', 'Invoices', 'Audit'],
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

  it('names the server-issued hospital and branch of a branch-bound ORGANIZATION acting context, with a neutral loading fallback', async () => {
    renderShell('ADMIN');

    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    // While the hierarchy loads: neutral — never "organization-wide" for a
    // token the server bound to one concrete hospital and branch.
    expect(screen.getByText('Main Hospital Group — loading branch…')).toBeInTheDocument();
    // Once loaded, the acting hospital and branch are named from the
    // server-derived slice and nothing else.
    expect(await screen.findByText('Main Hospital Group — Demo Central Hospital — East Clinic')).toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
    // The slice came from GET /api/network/hierarchy with the session token.
    const hierarchyCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/network/hierarchy');
    expect(hierarchyCalls).toHaveLength(1);
    expect(hierarchyCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
  });

  it('shows a neutral branch-id fallback when the acting branch is absent from the authorized slice', async () => {
    const state = { hierarchyBody: { ...HIERARCHY_VIEW, hospitals: [] } };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);
    renderShell('ADMIN');

    expect(await screen.findByText(`Main Hospital Group — branch ${EAST_BRANCH_ID}`)).toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
  });

  it('keeps the prior context and shows accessible text when the hierarchy cannot be loaded', async () => {
    const state = { hierarchyStatus: 403, hierarchyBody: { error: 'Forbidden' } };
    fetchMock = stubBackendApi(state);
    vi.stubGlobal('fetch', fetchMock);
    const { onSessionExpired } = renderShell('ADMIN');

    // A refused hierarchy read: the refusal is surfaced as text in the
    // selector, the session/context/selection stay untouched, and no
    // organization-wide claim is invented for the branch-bound token.
    expect(await screen.findByRole('alert')).toHaveTextContent(/permission to view this data/i);
    expect(screen.getByText(`Main Hospital Group — branch ${EAST_BRANCH_ID}`)).toBeInTheDocument();
    expect(screen.queryByText(/organization-wide/)).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('displays the hospital label of a fixed-scope acting context without fetching the hierarchy', async () => {
    renderShell('DOCTOR');

    expect(screen.getByText('Demo Central Hospital')).toBeInTheDocument();
    expect(screen.getByText('East Clinic')).toBeInTheDocument();
    await waitForDashboardStats();
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/network/hierarchy')).toHaveLength(0);
  });

  it('offers the network selector with the server-issued (assignment, hospital, branch) triples and a keyboard-operable native select', async () => {
    const user = userEvent.setup();
    renderShell('DOCTOR', [ASSIGNMENTS.ADMIN]);
    await waitForDashboardStats();

    const select = screen.getByRole('combobox', { name: 'Acting context' });
    // The acting pair (DOCTOR fixed on East) is the current selection.
    expect(select).toHaveValue(`${ASSIGNMENTS.DOCTOR.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`);
    await waitFor(() => expect(select.querySelectorAll('option')).toHaveLength(3));
    const options = [...select.querySelectorAll('option')];
    // The fixed assignment offers exactly its own hospital/branch pair; the
    // ORGANIZATION assignment offers each server-issued hospital branch.
    expect(options.map((option) => option.value)).toEqual([
      `${ASSIGNMENTS.DOCTOR.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`,
      `${ASSIGNMENTS.ADMIN.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`,
      `${ASSIGNMENTS.ADMIN.id}|${HOSPITAL_ID}|${WEST_BRANCH_ID}`,
    ]);
    expect(options[0]).toHaveTextContent('DOCTOR — East Clinic · Demo Central Hospital · Main Hospital Group');
    expect(options[1]).toHaveTextContent('ADMIN — East Clinic · Demo Central Hospital · Main Hospital Group');
    expect(options[2]).toHaveTextContent('ADMIN — West Clinic · Demo Central Hospital · Main Hospital Group');
    expect(screen.getByLabelText('Acting context')).toBe(select);

    // Keyboard operability: focus the labeled native control and switch.
    select.focus();
    expect(select).toHaveFocus();
    await user.selectOptions(select, `${ASSIGNMENTS.ADMIN.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`);

    await waitFor(() => expect(fetchMock.mock.calls.some(([path]) => path === '/api/auth/context')).toBe(true));
    const contextCall = fetchMock.mock.calls.find(([path]) => path === '/api/auth/context');
    expect(contextCall[1].method).toBe('POST');
    expect(contextCall[1].headers.Authorization).toBe('Bearer synthetic-token');
    // The selection target names the assignment AND the chosen hospital/branch.
    expect(JSON.parse(contextCall[1].body)).toEqual({
      assignmentId: ASSIGNMENTS.ADMIN.id,
      hospitalId: HOSPITAL_ID,
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
          hospitalId: HOSPITAL_ID,
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
      `${ASSIGNMENTS.ADMIN.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`,
    );

    // Whoami reflects the new acting context immediately, naming the hospital
    // and branch the new token is bound to — never an organization-wide claim.
    await waitFor(() => expect(screen.getByText('Main Hospital Group — Demo Central Hospital — East Clinic')).toBeInTheDocument());
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    // The selected patients view reloaded from the server under the new
    // context-bound token — a fresh fetch owned by the same screen effect.
    await screen.findAllByRole('list', { name: 'Patient results' });
    const patientCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/patients');
    expect(patientCalls.length).toBeGreaterThan(patientsCallsBefore);
    expect(patientCalls[patientCalls.length - 1][1].headers.Authorization).toBe('Bearer switched-context-token');
    // The switch request named the assignment, hospital, and chosen branch.
    const contextCall = fetchMock.mock.calls.find(([path]) => path === '/api/auth/context');
    expect(JSON.parse(contextCall[1].body)).toEqual({
      assignmentId: ASSIGNMENTS.ADMIN.id,
      hospitalId: HOSPITAL_ID,
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
          hospitalId: HOSPITAL_ID,
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
    await waitFor(() => expect(screen.getByText('Main Hospital Group — Demo Central Hospital — East Clinic')).toBeInTheDocument());

    // Same assignment, different branch: the triple target carries the change.
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ASSIGNMENTS.ADMIN.id}|${HOSPITAL_ID}|${WEST_BRANCH_ID}`,
    );

    // Whoami and storage now reflect the West-bound context.
    await waitFor(() => expect(screen.getByText('Main Hospital Group — Demo Central Hospital — West Clinic')).toBeInTheDocument());
    const contextCall = fetchMock.mock.calls.find(([path]) => path === '/api/auth/context');
    expect(JSON.parse(contextCall[1].body)).toEqual({
      assignmentId: ASSIGNMENTS.ADMIN.id,
      hospitalId: HOSPITAL_ID,
      branchId: WEST_BRANCH_ID,
    });
    expect(JSON.parse(sessionStorage.getItem('medicore.session')).token).toBe('west-context-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
    // The East branch line is gone — no stale claim survives the switch.
    expect(screen.queryByText('Main Hospital Group — Demo Central Hospital — East Clinic')).not.toBeInTheDocument();
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
      `${ASSIGNMENTS.ADMIN.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`,
    );

    // Understandable in-place denial — text, not color-only.
    expect(await screen.findByRole('alert')).toHaveTextContent(/refused this context switch/i);
    // Prior session fully preserved: identity, role, branch, and selection.
    expect(screen.getByText('DOCTOR')).toBeInTheDocument();
    expect(screen.getByText('East Clinic')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Acting context' })).toHaveValue(
      `${ASSIGNMENTS.DOCTOR.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`,
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
      `${ASSIGNMENTS.ADMIN.id}|${HOSPITAL_ID}|${WEST_BRANCH_ID}`,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('cannot paint a hierarchy response resolved under a switched acting context, not even for one render', async () => {
    const user = userEvent.setup();
    // The first context's hierarchy read is held until after the switch:
    // resolving it late must never paint the old-context slice, regardless
    // of effect cleanup — the render-phase tag gate owns that.
    const state = {
      contextStatus: 200,
      contextBody: {
        accessToken: 'switched-context-token',
        tokenType: 'Bearer',
        username: 'testuser',
        roles: ['BILLING'],
        assignments: [ASSIGNMENTS.BILLING, ASSIGNMENTS.ADMIN],
        actingContext: {
          username: 'testuser',
          assignmentId: ASSIGNMENTS.BILLING.id,
          role: 'BILLING',
          scope: 'BRANCH',
          organizationId: ORG_ID,
          hospitalId: HOSPITAL_ID,
          branchId: EAST_BRANCH_ID,
          departmentId: null,
        },
      },
    };
    const pendingHierarchy = [];
    const dataStub = stubBackendApi(state);
    fetchMock = vi.fn((path, options = {}) => {
      if (path === '/api/network/hierarchy') {
        return new Promise((resolve) => {
          pendingHierarchy.push({ token: options.headers.Authorization, resolve });
        });
      }
      return dataStub(path, options);
    });
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
    // Acting: RECEPTIONIST fixed on West; the BILLING fixed assignment gives
    // a switchable target while the hierarchy is pending, and the ADMIN
    // assignment makes the shell fetch the hierarchy at all.
    view = render(
      <AppShell
        session={fullSession('RECEPTIONIST', [ASSIGNMENTS.BILLING, ASSIGNMENTS.ADMIN])}
        onLogout={vi.fn()}
        onSessionExpired={onSessionExpired}
        onContextSwitch={switchAndApply}
      />
    );
    expect(screen.getByText('Demo Central Hospital')).toBeInTheDocument();
    expect(screen.getByText('West Clinic')).toBeInTheDocument();
    await waitFor(() => expect(pendingHierarchy).toHaveLength(1));
    expect(pendingHierarchy[0].token).toBe('Bearer synthetic-token');

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Acting context' }),
      `${ASSIGNMENTS.BILLING.id}|${HOSPITAL_ID}|${EAST_BRANCH_ID}`,
    );

    // The switch resolved: the whoami shows the new fixed-scope labels —
    // never the old context's hierarchy data.
    await waitFor(() => expect(screen.getByText('BILLING')).toBeInTheDocument());
    expect(screen.getByText('East Clinic')).toBeInTheDocument();

    // The OLD-context hierarchy response resolves late — the render-phase
    // gate must discard it because its context key no longer matches, so
    // the stale hospital cannot appear even for one render.
    const STALE_HOSPITAL_NAME = 'Stale Context Hospital';
    pendingHierarchy[0].resolve(jsonResponse({
      ...HIERARCHY_VIEW,
      hospitals: [{ ...HIERARCHY_VIEW.hospitals[0], name: STALE_HOSPITAL_NAME }],
    }));
    // The new context issues its own read under its own token.
    await waitFor(() => expect(pendingHierarchy).toHaveLength(2));
    expect(pendingHierarchy[1].token).toBe('Bearer switched-context-token');
    pendingHierarchy[1].resolve(jsonResponse({ ...HIERARCHY_VIEW, hospitals: [] }));
    await waitFor(() => expect(pendingHierarchy).toHaveLength(2));
    expect(screen.queryByText(STALE_HOSPITAL_NAME)).not.toBeInTheDocument();
    expect(screen.queryByText('Demo Central Hospital — West Clinic')).not.toBeInTheDocument();
    expect(screen.getByText('Demo Central Hospital')).toBeInTheDocument();
    expect(screen.getByText('East Clinic')).toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
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
        (session) => { session.actingContext.hospitalId = 'other-hospital'; },
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
