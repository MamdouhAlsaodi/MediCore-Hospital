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
    if (path === '/api/staff') return Promise.resolve(jsonResponse(STAFF_DIRECTORY));
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

    expect(navigationItems()).toEqual(['Dashboard', 'Patients', 'Appointments']);
    for (const unimplemented of ['Billing', 'Laboratory', 'Pharmacy', 'Audit']) {
      expect(screen.queryByRole('button', { name: unimplemented })).not.toBeInTheDocument();
    }
    await waitForDashboardStats();
  });

  it('hides clinical destinations from a role the server would reject', async () => {
    renderShell(['BILLING']);

    expect(navigationItems()).toEqual(['Dashboard']);
    expect(screen.queryByRole('button', { name: 'Patients' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Appointments' })).not.toBeInTheDocument();
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

  it('activates a destination from the keyboard', async () => {
    const user = userEvent.setup();
    renderShell(['DOCTOR']);
    await waitForDashboardStats();

    screen.getByRole('button', { name: 'Appointments' }).focus();
    await user.keyboard('{Enter}');

    expect(screen.getByRole('button', { name: 'Appointments' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Appointments' })).toBeInTheDocument();
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
