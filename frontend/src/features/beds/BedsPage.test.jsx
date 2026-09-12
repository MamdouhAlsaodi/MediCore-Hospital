import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BedsPage from './BedsPage.jsx';

// docs/plan3.md Task 6 DTO contract: id/branchId/ward/room/bedNumber/
// occupancyStatus — no persistence metadata and no legacy patient reference
// anywhere. OCCUPIED is admission-owned (Task 7): such a row offers no
// client status action at all.
const BED_AVAILABLE = {
  id: '99999999-9999-4999-8999-999999999921',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward A',
  room: '101',
  bedNumber: 'A-01',
  occupancyStatus: 'AVAILABLE',
};

const BED_MAINTENANCE = {
  id: '99999999-9999-4999-8999-999999999922',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward B',
  room: '202',
  bedNumber: 'B-02',
  occupancyStatus: 'MAINTENANCE',
};

const BED_OUT_OF_SERVICE = {
  id: '99999999-9999-4999-8999-999999999923',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward C',
  room: '303',
  bedNumber: 'C-03',
  occupancyStatus: 'OUT_OF_SERVICE',
};

const BED_OCCUPIED = {
  id: '99999999-9999-4999-8999-999999999924',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward D',
  room: '404',
  bedNumber: 'D-04',
  occupancyStatus: 'OCCUPIED',
};

const BEDS_INITIAL = [BED_AVAILABLE, BED_MAINTENANCE, BED_OCCUPIED];

const BEDS_AFTER_CREATE = [
  BED_AVAILABLE,
  BED_MAINTENANCE,
  BED_OCCUPIED,
  {
    id: '99999999-9999-4999-8999-999999999925',
    branchId: '44444444-4444-4444-8444-444444444444',
    ward: 'Ward E',
    room: '505',
    bedNumber: 'E-05',
    occupancyStatus: 'AVAILABLE',
  },
];

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function sessionFor(roles) {
  return { token: 'synthetic-token', username: 'testuser', roles };
}

function renderBeds(roles = ['ADMIN']) {
  const onSessionExpired = vi.fn();
  const view = render(<BedsPage session={sessionFor(roles)} onSessionExpired={onSessionExpired} />);
  return { onSessionExpired, ...view };
}

// Router over the consumed endpoint families. `state` is read live so tests
// can switch payloads and outcomes between calls.
function stubBackend(fetchMock, state) {
  fetchMock.mockImplementation((path, options = {}) => {
    const method = options.method ?? 'GET';
    if (path === '/api/beds') {
      if (method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        if (state.postResponse) return Promise.resolve(state.postResponse);
        // The server gained the row: the next list read reports it.
        state.bedList = BEDS_AFTER_CREATE;
        return Promise.resolve(jsonResponse(BEDS_AFTER_CREATE[3]));
      }
      return Promise.resolve(jsonResponse(state.bedList, state.bedListStatus ?? 200));
    }
    if (/^\/api\/beds\/[^/]+\/status$/.test(path)) {
      state.putCalls.push({ path, body: JSON.parse(options.body) });
      if (state.putResponse) return Promise.resolve(state.putResponse);
      const target = JSON.parse(options.body).status;
      return Promise.resolve(jsonResponse({ ...BED_AVAILABLE, occupancyStatus: target }));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
}

function bedsTable() {
  return screen.queryByRole('table', { name: 'Registered beds' });
}

describe('BedsPage (plan3.md Task 6)', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('shows the loading state before the list resolves', async () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));

    renderBeds();

    expect(screen.getByRole('status')).toHaveTextContent('Loading beds…');
    expect(bedsTable()).not.toBeInTheDocument();
  });

  it('renders the branch-scoped list from the DTO contract and never any persistence metadata', async () => {
    stubBackend(fetchMock, { bedList: BEDS_INITIAL });
    const { onSessionExpired } = renderBeds();

    const table = await screen.findByRole('table', { name: 'Registered beds' });
    expect(within(table).getAllByRole('row')).toHaveLength(4); // header + three records
    expect(within(table).getByText('Ward A')).toBeInTheDocument();
    expect(within(table).getByText('A-01')).toBeInTheDocument();
    expect(within(table).getByText('AVAILABLE')).toBeInTheDocument();
    expect(within(table).getByText('OCCUPIED')).toBeInTheDocument();
    // No raw entity metadata and no legacy patient reference may surface.
    expect(screen.queryByText(/createdAt/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/version/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/44444444-4444/i)).not.toBeInTheDocument();

    const listCalls = fetchMock.mock.calls.filter(([path]) => path === '/api/beds');
    expect(listCalls).toHaveLength(1);
    expect(listCalls[0][1].method).toBe('GET');
    expect(listCalls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows the empty state with role-honest guidance when the branch has no beds', async () => {
    stubBackend(fetchMock, { bedList: [] });

    renderBeds(['NURSE']);

    expect(await screen.findByText('No beds registered')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add bed' })).toBeInTheDocument();
    expect(bedsTable()).not.toBeInTheDocument();

    // A role without the create hint sees the same empty state without the
    // action (UI mirroring only; the backend stays authoritative).
    renderBeds(['BILLING']);
    await waitFor(() => expect(screen.getAllByText('No beds registered')).toHaveLength(2));
    expect(screen.getAllByRole('button', { name: 'Add bed' })).toHaveLength(1);
  });

  it('filters the loaded rows by ward, room, or bed number without refetching', async () => {
    const user = userEvent.setup();
    stubBackend(fetchMock, { bedList: BEDS_INITIAL });
    renderBeds();

    await screen.findByRole('table', { name: 'Registered beds' });
    const callsAfterLoad = fetchMock.mock.calls.length;

    await user.type(screen.getByLabelText('Filter beds'), 'Ward B');

    const table = screen.getByRole('table', { name: 'Registered beds' });
    expect(within(table).getAllByRole('row')).toHaveLength(2); // header + the matching row
    expect(within(table).getByText('B-02')).toBeInTheDocument();
    expect(within(table).queryByText('A-01')).not.toBeInTheDocument();
    // Client-side filtering never performs a second request.
    expect(fetchMock.mock.calls.length).toBe(callsAfterLoad);

    await user.clear(screen.getByLabelText('Filter beds'));
    await user.type(screen.getByLabelText('Filter beds'), 'no-such-bed');
    expect(screen.getByText('No beds match the filter')).toBeInTheDocument();

    await user.clear(screen.getByLabelText('Filter beds'));
    expect(screen.getByRole('table', { name: 'Registered beds' })).toBeInTheDocument();
    expect(screen.queryByText('No beds match the filter')).not.toBeInTheDocument();
  });

  it('rejects an incomplete add form locally without sending any request', async () => {
    const user = userEvent.setup();
    stubBackend(fetchMock, { bedList: BEDS_INITIAL });
    renderBeds();

    await screen.findByRole('table', { name: 'Registered beds' });
    await user.click(screen.getByRole('button', { name: 'Add bed' }));

    await user.click(screen.getByRole('button', { name: 'Save bed' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Provide the ward, the room, and the bed number.'
    );
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/beds')).toHaveLength(1); // the list only
  });

  it('creates a bed with exactly the three contract fields and then refetches', async () => {
    const user = userEvent.setup();
    const state = { bedList: BEDS_INITIAL, postCalls: [] };
    stubBackend(fetchMock, state);
    renderBeds(['RECEPTIONIST']);

    await screen.findByRole('table', { name: 'Registered beds' });
    await user.click(screen.getByRole('button', { name: 'Add bed' }));

    await user.type(screen.getByLabelText('Ward'), 'Ward E');
    await user.type(screen.getByLabelText('Room'), '505');
    await user.type(screen.getByLabelText('Bed number'), 'E-05');
    await user.click(screen.getByRole('button', { name: 'Save bed' }));

    expect(state.postCalls).toHaveLength(1);
    // The adapter deliberately sends exactly CreateBedRequest — no branch,
    // status, or patient field can ever leave the client.
    expect(state.postCalls[0]).toEqual({ ward: 'Ward E', room: '505', bedNumber: 'E-05' });
    expect(await screen.findByText('Bed added.')).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Registered beds' });
    expect(within(table).getByText('E-05')).toBeInTheDocument();
    // The list refetches instead of patching server state locally.
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/beds')).toHaveLength(3);
  });

  it('surfaces the create conflict (409) inline and keeps the form values', async () => {
    const user = userEvent.setup();
    const state = { bedList: BEDS_INITIAL, postCalls: [], postResponse: jsonResponse({}, 409) };
    stubBackend(fetchMock, state);
    renderBeds();

    await screen.findByRole('table', { name: 'Registered beds' });
    await user.click(screen.getByRole('button', { name: 'Add bed' }));
    await user.type(screen.getByLabelText('Ward'), 'Ward A');
    await user.type(screen.getByLabelText('Room'), '101');
    await user.type(screen.getByLabelText('Bed number'), 'A-01');
    await user.click(screen.getByRole('button', { name: 'Save bed' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/The request failed \(409\)/);
    // Zero field loss on a refused submit.
    expect(screen.getByLabelText('Ward')).toHaveValue('Ward A');
    expect(screen.getByLabelText('Room')).toHaveValue('101');
    expect(screen.getByLabelText('Bed number')).toHaveValue('A-01');
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/beds')).toHaveLength(2);
  });

  it('offers exactly the legal status actions per row and none for an OCCUPIED bed', async () => {
    stubBackend(fetchMock, { bedList: [BED_AVAILABLE, BED_MAINTENANCE, BED_OUT_OF_SERVICE, BED_OCCUPIED] });
    renderBeds();

    const table = await screen.findByRole('table', { name: 'Registered beds' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(5); // header + four records

    const availableRow = rows[1];
    expect(
      within(availableRow).getByRole('button', { name: 'Start maintenance' })
    ).toBeInTheDocument();
    expect(
      within(availableRow).getByRole('button', { name: 'Take out of service' })
    ).toBeInTheDocument();
    expect(within(availableRow).queryByRole('button', { name: 'Return to service' })).not.toBeInTheDocument();

    const maintenanceRow = rows[2];
    expect(
      within(maintenanceRow).getByRole('button', { name: 'Return to service' })
    ).toBeInTheDocument();
    expect(
      within(maintenanceRow).getByRole('button', { name: 'Take out of service' })
    ).toBeInTheDocument();

    const outOfServiceRow = rows[3];
    expect(
      within(outOfServiceRow).getByRole('button', { name: 'Return to service' })
    ).toBeInTheDocument();
    expect(
      within(outOfServiceRow).getByRole('button', { name: 'Start maintenance' })
    ).toBeInTheDocument();

    // OCCUPIED is admission-owned: the occupied row carries no action.
    const occupiedRow = rows[4];
    expect(within(occupiedRow).queryByRole('button')).not.toBeInTheDocument();
    expect(within(occupiedRow).getByText('OCCUPIED')).toBeInTheDocument();
  });

  it('runs a two-step confirmed status transition and refetches the list', async () => {
    const user = userEvent.setup();
    const state = { bedList: BEDS_INITIAL, putCalls: [] };
    stubBackend(fetchMock, state);
    renderBeds();

    const table = await screen.findByRole('table', { name: 'Registered beds' });
    await user.click(within(table).getByRole('button', { name: 'Start maintenance' }));

    // Two-step confirmation: nothing is sent until Confirm is clicked.
    expect(state.putCalls).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Confirm Start maintenance' }));
    expect(state.putCalls).toHaveLength(1);
    expect(state.putCalls[0].body).toEqual({ status: 'MAINTENANCE' });
    expect(state.putCalls[0].path).toBe(`/api/beds/${BED_AVAILABLE.id}/status`);
    expect(await screen.findByText('Status updated.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/beds')).toHaveLength(2);
  });

  it('surfaces the transition conflict (409) inline with no partial success', async () => {
    const user = userEvent.setup();
    const state = {
      bedList: BEDS_INITIAL,
      putCalls: [],
      putResponse: jsonResponse({}, 409),
    };
    stubBackend(fetchMock, state);
    renderBeds();

    const table = await screen.findByRole('table', { name: 'Registered beds' });
    await user.click(within(table).getByRole('button', { name: 'Start maintenance' }));
    await user.click(await screen.findByRole('button', { name: 'Confirm Start maintenance' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/The request failed \(409\)/);
    expect(state.putCalls).toHaveLength(1);
    expect(screen.queryByText('Status updated.')).not.toBeInTheDocument();
    // The refused transition never triggers a refetch.
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/beds')).toHaveLength(1);
  });

  it('surfaces the server denial when the acting role is refused the read', async () => {
    stubBackend(fetchMock, { bedList: [], bedListStatus: 403 });
    renderBeds(['BILLING']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/permission/i);
    expect(bedsTable()).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add bed' })).not.toBeInTheDocument();
  });

  it('never renders the session token anywhere on the screen', async () => {
    stubBackend(fetchMock, { bedList: BEDS_INITIAL });
    renderBeds();

    await screen.findByRole('table', { name: 'Registered beds' });
    expect(document.body).not.toHaveTextContent('synthetic-token');
  });

  it('delegates 401 to the shell through the session-expiry callback', async () => {
    stubBackend(fetchMock, { bedList: [], bedListStatus: 401 });
    const { onSessionExpired } = renderBeds();

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
  });
});
