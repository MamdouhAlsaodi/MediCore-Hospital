import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import DashboardPage from './DashboardPage.jsx';

// Task 5 dashboard contract (docs/plan2.md): the five original totals, the
// flat status keys, and one unknown key to exercise the generic fallback.
const DASHBOARD_STATS = {
  patients: 7,
  appointments: 12,
  admissions: 3,
  emergencyVisits: 5,
  invoices: 4,
  openAdmissions: 2,
  activeEmergencyVisits: 3,
  invoicesDraft: 1,
  invoicesIssued: 1,
  invoicesPaid: 2,
  invoicesVoid: 0,
  loyaltyPoints: 99,
};

const SESSION = { token: 'synthetic-token', username: 'testuser', roles: ['DOCTOR'] };

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderDashboard({ onSessionExpired = vi.fn() } = {}) {
  render(<DashboardPage session={SESSION} onSessionExpired={onSessionExpired} />);
  return { onSessionExpired };
}

describe('DashboardPage', () => {
  let fetchMock;

  async function renderDashboardWithStats(stats = DASHBOARD_STATS) {
    fetchMock = vi.fn((path) => {
      if (path === '/api/dashboard') return Promise.resolve(jsonResponse(stats));
      return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const handle = renderDashboard();
    await screen.findByText('Patients');
    return handle;
  }

  beforeEach(() => {
    fetchMock = vi.fn((path) => {
      if (path === '/api/dashboard') return Promise.resolve(jsonResponse(DASHBOARD_STATS));
      return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  it('loads the dashboard through apiFetch and shows a loading state first', async () => {
    let resolveDashboard;
    fetchMock = vi.fn(() => new Promise((resolve) => { resolveDashboard = resolve; }));
    vi.stubGlobal('fetch', fetchMock);

    renderDashboard();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/dashboard', expect.anything());

    resolveDashboard(jsonResponse(DASHBOARD_STATS));
    await screen.findByText('Patients');
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
  });

  it('renders the grouped labeled sections including zero and unknown-key values', async () => {
    await renderDashboardWithStats();

    const currentActivity = screen.getByRole('region', { name: 'Current activity' });
    expect(within(currentActivity).getByText('Open admissions')).toBeInTheDocument();
    expect(within(currentActivity).getByText('2')).toBeInTheDocument();
    expect(within(currentActivity).getByText('Active emergency visits')).toBeInTheDocument();
    expect(within(currentActivity).getByText('3')).toBeInTheDocument();

    const totals = screen.getByRole('region', { name: 'Totals' });
    for (const [label, value] of [
      ['Patients', '7'], ['Appointments', '12'], ['Admissions', '3'],
      ['Emergency visits', '5'], ['Invoices', '4'],
    ]) {
      expect(within(totals).getByText(label)).toBeInTheDocument();
      expect(within(totals).getByText(value)).toBeInTheDocument();
    }

    const invoiceStatuses = screen.getByRole('region', { name: 'Invoices by status' });
    expect(within(invoiceStatuses).getByText('Draft')).toBeInTheDocument();
    expect(within(invoiceStatuses).getByText('Issued')).toBeInTheDocument();
    expect(within(invoiceStatuses).getByText('Paid')).toBeInTheDocument();
    expect(within(invoiceStatuses).getByText('Void')).toBeInTheDocument();
    expect(within(invoiceStatuses).getByText('0')).toBeInTheDocument();

    // Unknown response keys keep the generic fallback without duplicating
    // any key that already has a labeled home.
    const fallback = screen.getByRole('region', { name: 'Other reported keys' });
    expect(within(fallback).getByText('loyaltyPoints')).toBeInTheDocument();
    expect(within(fallback).getByText('99')).toBeInTheDocument();
    expect(within(fallback).queryByText('Patients')).not.toBeInTheDocument();
    expect(within(fallback).queryByText('Void')).not.toBeInTheDocument();
  });

  it('renders an honest missing marker instead of a fabricated zero for absent known keys', async () => {
    const withoutOpenAdmissions = { ...DASHBOARD_STATS };
    delete withoutOpenAdmissions.openAdmissions;
    await renderDashboardWithStats(withoutOpenAdmissions);

    const currentActivity = screen.getByRole('region', { name: 'Current activity' });
    expect(within(currentActivity).getByText('Open admissions')).toBeInTheDocument();
    expect(within(currentActivity).getByText('—')).toBeInTheDocument();
    expect(within(currentActivity).queryByText('0')).not.toBeInTheDocument();
  });

  it('shows the API error message for a failed load', async () => {
    fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ error: 'boom' }, 500)));
    vi.stubGlobal('fetch', fetchMock);

    renderDashboard();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The request failed (500). Please try again.'
    );
  });

  it('invokes onSessionExpired on a 401 without stacking a local error', async () => {
    fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ error: 'Unauthorized' }, 401)));
    vi.stubGlobal('fetch', fetchMock);

    const { onSessionExpired } = renderDashboard();
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
