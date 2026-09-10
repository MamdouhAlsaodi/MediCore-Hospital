import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AppShell from './AppShell.jsx';

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

function stubBackendApi() {
  return vi.fn((path) => {
    if (path === '/api/dashboard') return Promise.resolve(jsonResponse(DASHBOARD_STATS));
    if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS_PAGE));
    if (path === '/api/appointments') return Promise.resolve(jsonResponse(APPOINTMENTS_PAGE));
    if (path === '/api/admissions') return Promise.resolve(jsonResponse(ADMISSIONS_PAGE));
    if (path === '/api/emergency-visits') return Promise.resolve(jsonResponse(EMERGENCY_VISITS_PAGE));
    if (path === '/api/staff') return Promise.resolve(jsonResponse(STAFF_DIRECTORY));
    if (path === '/api/audit') return Promise.resolve(jsonResponse(AUDIT_EVENTS));
    return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
  });
}

function renderShell(roles) {
  return render(
    <AppShell
      session={{ token: 'synthetic-token', username: 'testuser', roles }}
      onLogout={vi.fn()}
      onSessionExpired={vi.fn()}
    />
  );
}

async function waitForDashboardStats() {
  await screen.findByText('patients');
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
    for (const unimplemented of ['Billing', 'Laboratory', 'Pharmacy', 'Beds']) {
      expect(screen.queryByRole('button', { name: unimplemented })).not.toBeInTheDocument();
    }
    // The audit evidence screen is implemented but ADMIN-only (plan1.md
    // Task 10): DOCTOR never sees the destination.
    expect(screen.queryByRole('button', { name: 'Audit' })).not.toBeInTheDocument();
    await waitForDashboardStats();
  });

  it('hides clinical destinations from a role the server would reject', async () => {
    renderShell(['BILLING']);

    expect(navigationItems()).toEqual(['Dashboard']);
    for (const denied of ['Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Audit']) {
      expect(screen.queryByRole('button', { name: denied })).not.toBeInTheDocument();
    }
    await waitForDashboardStats();
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
    expect(screen.queryByText('patients')).not.toBeInTheDocument();
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
      ['Dashboard', 'Patients', 'Appointments', 'Admissions', 'Emergency Visits', 'Audit'],
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
        session={{ token: 'synthetic-token', username: 'testuser', roles: ['DOCTOR'] }}
        onLogout={onLogout}
        onSessionExpired={vi.fn()}
      />
    );
    await waitForDashboardStats();

    await user.click(screen.getByRole('button', { name: 'Log out' }));

    expect(onLogout).toHaveBeenCalledTimes(1);
  });
});
