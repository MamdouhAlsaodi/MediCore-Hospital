import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DashboardPage, { branchDisplayState, networkDisplayState } from './DashboardPage.jsx';

// Command-center contract (docs/plan3.md Task 10): the screen renders the
// server's typed branch summary for every permitted role and the network
// comparison only for a server-issued ADMIN + ORGANIZATION acting context.
// The transport goes through the shared adapter; the server stays the sole
// authority over scope, values, and ordering.
const ORG_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const EAST_BRANCH_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const WEST_BRANCH_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

function summaryFor(branchId, code, name, metrics) {
  return {
    branchId,
    branchCode: code,
    branchName: name,
    patients: 0, appointments: 0, admissions: 0, emergencyVisits: 0, invoices: 0,
    openAdmissions: 0, activeEmergencyVisits: 0,
    bedsAvailable: 0, bedsOccupied: 0, bedsMaintenance: 0, bedsOutOfService: 0,
    todayAppointments: 0,
    invoicesDraft: 0, invoicesIssued: 0, invoicesPaid: 0, invoicesVoid: 0,
    ...metrics,
  };
}

const BRANCH_SUMMARY = summaryFor(EAST_BRANCH_ID, 'EAST', 'East Clinic', {
  patients: 7, appointments: 12, admissions: 3, emergencyVisits: 5, invoices: 4,
  openAdmissions: 2, activeEmergencyVisits: 3,
  bedsAvailable: 4, bedsOccupied: 2, bedsMaintenance: 1, bedsOutOfService: 0,
  todayAppointments: 6,
  invoicesDraft: 1, invoicesIssued: 1, invoicesPaid: 2, invoicesVoid: 0,
});

const NETWORK_SUMMARY = {
  organizationId: ORG_ID,
  organizationName: 'Main Hospital Group',
  patients: 7, appointments: 12, admissions: 3, emergencyVisits: 5, invoices: 4,
  openAdmissions: 2, activeEmergencyVisits: 3,
  bedsAvailable: 4, bedsOccupied: 2, bedsMaintenance: 1, bedsOutOfService: 0,
  todayAppointments: 6,
  invoicesDraft: 1, invoicesIssued: 1, invoicesPaid: 2, invoicesVoid: 0,
  branches: [
    BRANCH_SUMMARY,
    summaryFor(WEST_BRANCH_ID, 'WEST', 'West Clinic', {}),
  ],
};

// Complete server-issued session shape (docs/plan3.md Task 3) with one
// acting context — the only role/scope data the screen may consult, and
// only as a display gate; the server re-checks every request.
function sessionFor(role, scope, branchId) {
  const assignmentId = `assign-${role}-${scope}`;
  return {
    token: 'synthetic-token',
    username: 'testuser',
    roles: [role],
    assignments: [{
      id: assignmentId,
      role,
      scope,
      organizationId: ORG_ID,
      organizationLabel: 'Main Hospital Group',
      branchId,
      branchLabel: 'East Clinic',
      departmentId: null,
      departmentLabel: null,
      enabled: true,
    }],
    actingContext: {
      username: 'testuser',
      assignmentId,
      role,
      scope,
      organizationId: ORG_ID,
      branchId,
      departmentId: null,
    },
  };
}

const DOCTOR_SESSION = sessionFor('DOCTOR', 'BRANCH', EAST_BRANCH_ID);
const ORG_ADMIN_SESSION = sessionFor('ADMIN', 'ORGANIZATION', EAST_BRANCH_ID);

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubBackend({ branch = BRANCH_SUMMARY, branchStatus = 200, network = NETWORK_SUMMARY, networkStatus = 200 } = {}) {
  return vi.fn((path) => {
    if (path === '/api/dashboard') return Promise.resolve(jsonResponse(branch, branchStatus));
    if (path === '/api/dashboard/network') return Promise.resolve(jsonResponse(network, networkStatus));
    return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
  });
}

// The stat card (article) that carries a labeled key inside a group.
function cardFor(region, label) {
  return within(region).getByText(label).closest('article');
}

function renderDashboard(session, { onSessionExpired = vi.fn(), onNavigate = vi.fn() } = {}) {
  const fetchMock = stubBackend();
  vi.stubGlobal('fetch', fetchMock);
  render(
    <DashboardPage
      session={session}
      onSessionExpired={onSessionExpired}
      onNavigate={onNavigate}
    />
  );
  return { fetchMock, onSessionExpired, onNavigate };
}

describe('DashboardPage', () => {
  it('loads the branch summary through the transport adapter and shows a loading state first', async () => {
    let resolveBranch;
    const fetchMock = vi.fn((path) => {
      if (path === '/api/dashboard') {
        return new Promise((resolve) => { resolveBranch = resolve; });
      }
      return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<DashboardPage session={DOCTOR_SESSION} onSessionExpired={vi.fn()} onNavigate={vi.fn()} />);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/dashboard', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer synthetic-token' }),
    }));

    resolveBranch(jsonResponse(BRANCH_SUMMARY));
    await screen.findByText('Patients');
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
  });

  it('renders the populated branch summary: identity, totals, activity, beds, and invoice buckets', async () => {
    renderDashboard(DOCTOR_SESSION);
    expect(await screen.findByText('EAST — East Clinic')).toBeInTheDocument();

    const currentActivity = screen.getByRole('region', { name: 'Current activity' });
    expect(within(currentActivity).getByText('Open admissions')).toBeInTheDocument();
    expect(within(currentActivity).getByText('2')).toBeInTheDocument();
    expect(within(currentActivity).getByText('Active emergency visits')).toBeInTheDocument();
    expect(within(currentActivity).getByText('3')).toBeInTheDocument();
    expect(within(currentActivity).getByText("Today's appointments")).toBeInTheDocument();
    expect(within(currentActivity).getByText('6')).toBeInTheDocument();

    const totals = screen.getByRole('region', { name: 'Totals' });
    for (const [label, value] of [
      ['Patients', '7'], ['Appointments', '12'], ['Admissions', '3'],
      ['Emergency visits', '5'], ['Invoices', '4'],
    ]) {
      expect(within(cardFor(totals, label)).getByText(value)).toBeInTheDocument();
    }

    const beds = screen.getByRole('region', { name: 'Beds by status' });
    for (const [label, value] of [
      ['Available', '4'], ['Occupied', '2'], ['Maintenance', '1'], ['Out of service', '0'],
    ]) {
      expect(within(cardFor(beds, label)).getByText(value)).toBeInTheDocument();
    }

    const invoiceStatuses = screen.getByRole('region', { name: 'Invoices by status' });
    for (const [label, value] of [
      ['Draft', '1'], ['Issued', '1'], ['Paid', '2'], ['Void', '0'],
    ]) {
      expect(within(cardFor(invoiceStatuses, label)).getByText(value)).toBeInTheDocument();
    }
  });

  it('renders the honest missing marker and the generic fallback section for unusual payloads', async () => {
    const { patients } = BRANCH_SUMMARY;
    vi.stubGlobal('fetch', vi.fn((path) => {
      if (path === '/api/dashboard') {
        return Promise.resolve(jsonResponse({ patients, loyaltyPoints: 99 }));
      }
      return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
    }));

    render(<DashboardPage session={DOCTOR_SESSION} onSessionExpired={vi.fn()} onNavigate={vi.fn()} />);
    await screen.findByText('Patients');

    const currentActivity = screen.getByRole('region', { name: 'Current activity' });
    expect(within(currentActivity).getByText('Open admissions')).toBeInTheDocument();
    const openAdmissionsCard = cardFor(currentActivity, 'Open admissions');
    expect(within(openAdmissionsCard).getByText('—')).toBeInTheDocument();
    expect(within(openAdmissionsCard).queryByText('0')).not.toBeInTheDocument();

    const fallback = screen.getByRole('region', { name: 'Other reported keys' });
    expect(within(fallback).getByText('loyaltyPoints')).toBeInTheDocument();
    expect(within(fallback).getByText('99')).toBeInTheDocument();
  });

  it('renders the network comparison only for the server-issued ADMIN + ORGANIZATION context', async () => {
    renderDashboard(ORG_ADMIN_SESSION);

    expect(await screen.findByRole('region', { name: 'Network comparison' })).toBeInTheDocument();
    expect(screen.getByText('Main Hospital Group')).toBeInTheDocument();
    // Deterministic server order: EAST first, then the all-zero WEST summary.
    const network = screen.getByRole('region', { name: 'Network comparison' });
    const branchRows = within(network).getAllByRole('listitem');
    expect(branchRows).toHaveLength(2);
    expect(branchRows[0]).toHaveTextContent('EAST — East Clinic');
    expect(branchRows[1]).toHaveTextContent('WEST — West Clinic');
    // The branch without records appears honestly as an all-zero summary.
    expect(branchRows[1]).toHaveTextContent('Patients 0');
    expect(branchRows[1]).toHaveTextContent("Today's appointments 0");
  });

  it('never requests the network comparison for a branch-scoped context', async () => {
    const { fetchMock } = renderDashboard(DOCTOR_SESSION);
    await screen.findByText('Patients');

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/dashboard', expect.anything()));
    const networkCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/dashboard/network');
    expect(networkCalls).toHaveLength(0);
    expect(screen.queryByRole('region', { name: 'Network comparison' })).not.toBeInTheDocument();
  });

  it('shows a network denial inline, keeps the branch summary, and preserves the context', async () => {
    const fetchMock = vi.fn((path) => {
      if (path === '/api/dashboard') return Promise.resolve(jsonResponse(BRANCH_SUMMARY));
      if (path === '/api/dashboard/network') return Promise.resolve(jsonResponse({ error: 'denied' }, 403));
      return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
    });
    vi.stubGlobal('fetch', fetchMock);
    const onSessionExpired = vi.fn();
    render(<DashboardPage session={ORG_ADMIN_SESSION} onSessionExpired={onSessionExpired} onNavigate={vi.fn()} />);

    expect(await screen.findByText('You do not have permission to view this data.')).toBeInTheDocument();
    // The branch summary and the acting context survive the denial untouched.
    expect(screen.getByText('EAST — East Clinic')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Totals' })).toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('renders a malformed network response as an inline refusal instead of improvising', async () => {
    const fetchMock = vi.fn((path) => {
      if (path === '/api/dashboard') return Promise.resolve(jsonResponse(BRANCH_SUMMARY));
      if (path === '/api/dashboard/network') return Promise.resolve(jsonResponse({ patients: 'many' }));
      return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<DashboardPage session={ORG_ADMIN_SESSION} onSessionExpired={vi.fn()} onNavigate={vi.fn()} />);

    expect(
      await screen.findByText('The server returned an unexpected network response.')
    ).toBeInTheDocument();
    expect(screen.getByText('EAST — East Clinic')).toBeInTheDocument();
  });

  it('shows the API error message for a failed branch load', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ error: 'boom' }, 500))));
    render(<DashboardPage session={DOCTOR_SESSION} onSessionExpired={vi.fn()} onNavigate={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The request failed (500). Please try again.'
    );
  });

  it('invokes onSessionExpired on a 401 without stacking a local error', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ error: 'Unauthorized' }, 401))));
    const onSessionExpired = vi.fn();
    render(<DashboardPage session={ORG_ADMIN_SESSION} onSessionExpired={onSessionExpired} onNavigate={vi.fn()} />);
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('offers drill-down only to implemented screens that already expose their own filters', async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    const { fetchMock } = renderDashboard(DOCTOR_SESSION, { onNavigate });
    await screen.findByText('Patients');

    const patientsCard = screen.getByRole('button', { name: 'View patients' });
    expect(patientsCard).toHaveAccessibleName('View patients');
    const bedsCard = screen.getByRole('button', { name: 'View beds' });
    expect(bedsCard).toHaveAccessibleName('View beds');

    await user.click(patientsCard);
    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onNavigate).toHaveBeenCalledWith('patients');
    await user.click(bedsCard);
    expect(onNavigate).toHaveBeenLastCalledWith('beds');

    // Cards whose target screens have no safe filter input stay
    // non-actionable — no fabricated client-side filtering.
    expect(screen.queryByRole('button', { name: 'View appointments' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View admissions' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View emergency visits' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View invoices' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/patients')).toHaveLength(0);
  });

  it('keeps a card non-actionable when the acting role cannot reach the target screen', async () => {
    const BILLING_SESSION = sessionFor('BILLING', 'BRANCH', EAST_BRANCH_ID);
    renderDashboard(BILLING_SESSION);
    await screen.findByText('Patients');

    expect(screen.queryByRole('button', { name: 'View patients' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View beds' })).not.toBeInTheDocument();
  });

  it('cannot paint a response resolved under a switched acting context', async () => {
    const deferred = [];
    const fetchMock = vi.fn((path) => {
      if (path === '/api/dashboard') {
        return new Promise((resolve) => {
          deferred.push(resolve);
        });
      }
      return Promise.resolve(jsonResponse({ error: 'not found' }, 404));
    });
    vi.stubGlobal('fetch', fetchMock);
    const view = render(
      <DashboardPage session={DOCTOR_SESSION} onSessionExpired={vi.fn()} onNavigate={vi.fn()} />
    );

    // The shell swaps in a switched session while the first read is in
    // flight; the render-phase gate must keep showing loading, never the
    // response resolved under the previous context.
    view.rerender(
      <DashboardPage session={sessionFor('ADMIN', 'ORGANIZATION', WEST_BRANCH_ID)} onSessionExpired={vi.fn()} onNavigate={vi.fn()} />
    );
    deferred[0](jsonResponse(summaryFor(EAST_BRANCH_ID, 'EAST', 'East Clinic', { patients: 7 })));
    // The switched context issues its own read; the late A-tagged response
    // resolves but must never paint under B.
    await waitFor(() => expect(deferred.length).toBe(2));
    expect(screen.queryByText('EAST — East Clinic')).not.toBeInTheDocument();
    expect(screen.queryByText('7')).not.toBeInTheDocument();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  describe('render-phase context gate contract', () => {
    const KEY_A = 'assign-a:branch-a';
    const KEY_B = 'assign-b:branch-b';
    const readyA = { contextKey: KEY_A, status: 'ready', loadError: '', summary: BRANCH_SUMMARY };

    it('exposes data loaded under context A while A is current', () => {
      const display = branchDisplayState({ contextKey: KEY_A, loaded: readyA });
      expect(display).toBe(readyA);
      expect(display.summary.patients).toBe(7);
    });

    it('stops exposing that data the moment the context becomes B and shows loading', () => {
      const display = branchDisplayState({ contextKey: KEY_B, loaded: readyA });
      expect(display).not.toBe(readyA);
      expect(display.status).toBe('loading');
      expect(display.summary).toBeNull();
      expect(display.loadError).toBe('');
    });

    it('refuses a late A-tagged publication while B is current', () => {
      const lateA = { contextKey: KEY_A, status: 'error', loadError: 'stale failure', summary: null };
      const display = branchDisplayState({ contextKey: KEY_B, loaded: lateA });
      expect(display.status).toBe('loading');
      expect(display.loadError).toBe('');
    });

    it('gates the network section the same way', () => {
      const loadedA = { contextKey: KEY_A, status: 'ready', loadError: '', summary: NETWORK_SUMMARY };
      expect(networkDisplayState({ contextKey: KEY_A, enabled: true, loaded: loadedA })).toBe(loadedA);
      const gated = networkDisplayState({ contextKey: KEY_B, enabled: true, loaded: loadedA });
      expect(gated.status).toBe('loading');
      expect(gated.summary).toBeNull();
      // The network section is never exposed when the context does not allow it.
      expect(networkDisplayState({ contextKey: KEY_A, enabled: false, loaded: loadedA }).status).toBe('hidden');
    });
  });
});
