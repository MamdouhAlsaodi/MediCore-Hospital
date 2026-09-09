import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AppShell from './AppShell.jsx';

const DASHBOARD_STATS = { patients: 12, appointmentsToday: 4 };

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubBackendApi() {
  return vi.fn((path) => {
    if (path === '/api/dashboard') return Promise.resolve(jsonResponse(DASHBOARD_STATS));
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
    const appointmentsBoundary = screen.getByRole('region', { name: 'Appointments screen' });
    expect(appointmentsBoundary).toHaveTextContent(/not implemented/i);
    expect(appointmentsBoundary).toHaveTextContent('Task 8');
    expect(screen.queryByText('patients')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(dashboardCalls);

    await user.click(screen.getByRole('button', { name: 'Patients' }));

    expect(screen.getByRole('button', { name: 'Patients' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('heading', { name: 'Patients' })).toBeInTheDocument();
    const patientsBoundary = screen.getByRole('region', { name: 'Patients screen' });
    expect(patientsBoundary).toHaveTextContent(/not implemented/i);
    expect(patientsBoundary).toHaveTextContent('Task 6');
    expect(fetchMock).toHaveBeenCalledTimes(dashboardCalls);

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
