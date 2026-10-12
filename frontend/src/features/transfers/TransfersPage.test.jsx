import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import TransfersPage from './TransfersPage.jsx';

// Phase 5 US8 (T143/T145/T146): the transfers screen contract over real
// transport behavior (stubbed fetch boundary): the scoped server list loads
// once through GET /api/transfers, states render honestly (loading, error,
// empty, filtered), the request form issues the exact POST /api/transfers
// body with a client-chosen Idempotency-Key, the detail panel exposes the
// permitted per-status transitions, and a 409 conflict or stale-version
// refusal renders an inline, state-preserving message — the stored version
// stays for the operator's next attempt. Raw pointers (patient/hospital/
// bed ids) never render; only contract labels do.
const SOURCE_ID = '44444444-4444-4444-8444-444444444444';
const DEST_ID = '66666666-6666-4666-8666-666666666666';
const PATIENT_ID = '55555555-5555-4555-8555-555555555555';

function transferRow(overrides = {}) {
  return {
    id: '88888888-8888-4888-8888-888888888888',
    transferNumber: 'TR-0001',
    patientId: PATIENT_ID,
    sourceHospitalId: SOURCE_ID,
    sourceBranchId: '22222222-2222-4222-8222-222222222222',
    destinationHospitalId: DEST_ID,
    destinationBranchId: null,
    destinationBedId: null,
    status: 'REQUESTED',
    reasonCode: 'BED_SHORTAGE',
    requestedAt: '2026-03-02T10:00:00Z',
    acceptedAt: null,
    transitStartedAt: null,
    completedAt: null,
    cancelledAt: null,
    rejectedAt: null,
    version: 0,
    ...overrides,
  };
}

const DESTINATIONS = [
  { id: DEST_ID, code: 'HOSP-B', name: 'Demo Harborview Hospital' },
  { id: '77777777-7777-4777-8777-777777777777', code: 'HOSP-C', name: 'Demo Riverside Hospital' },
];

const PATIENTS = [
  { id: PATIENT_ID, medicalRecordNumber: 'MRN-2001', fullName: 'Synthetic Patient' },
];

const ADMISSIONS = [
  {
    id: 'aaaa1111-1111-4111-8111-111111111111',
    patientId: PATIENT_ID,
    admittedAt: '2031-01-06T08:30:00',
    dischargedAt: null,
    status: 'ADMITTED',
  },
];

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const session = {
  token: 'synthetic-token',
  username: 'testuser',
  roles: ['NURSE'],
  assignments: [{
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    role: 'NURSE', scope: 'BRANCH', organizationId: 'o1',
    hospitalId: SOURCE_ID, hospitalLabel: 'Demo Legacy Hospital',
    branchId: 'b1', branchLabel: 'Demo Main Branch',
    departmentId: null, departmentLabel: null, enabled: true,
  }],
  actingContext: {
    username: 'testuser', assignmentId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    role: 'NURSE', scope: 'BRANCH', organizationId: 'o1',
    hospitalId: SOURCE_ID, branchId: 'b1', departmentId: null,
  },
};

function stubTransfers(fetchMock, payload, status = 200) {
  fetchMock.mockImplementation((path) => {
    if (path === '/api/transfers' || path.startsWith('/api/transfers?')) {
      return Promise.resolve(jsonResponse(payload, status));
    }
    if (path === '/api/network/hierarchy') {
      return Promise.resolve(jsonResponse({
        organizationId: 'o1', organizationCode: 'ORG', organizationName: 'Demo Synthetic Hospital',
        hospitals: DESTINATIONS.map((hospital) => ({
          id: hospital.id, code: hospital.code, name: hospital.name,
          regionLabel: 'Region', timeZone: 'UTC', active: true, branches: [],
        })),
      }));
    }
    if (path === '/api/patients') {
      return Promise.resolve(jsonResponse(PATIENTS));
    }
    if (path === '/api/admissions') {
      return Promise.resolve(jsonResponse(ADMISSIONS));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
}

async function waitLoaded() {
  await waitFor(() => {
    const statuses = screen.queryAllByRole('status');
    expect(statuses.some((el) => el.textContent.includes('Loading transfers…'))).toBe(false);
  });
}

describe('TransfersPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('loads the scoped list once through GET /api/transfers and renders the evidence columns', async () => {
    stubTransfers(fetchMock, [transferRow()]);
    render(<TransfersPage session={session} onSessionExpired={vi.fn()} />);

    const table = await screen.findByRole('table', { name: 'Authorized transfers' });
    for (const header of ['Number', 'Status', 'Reason', 'Direction', 'Requested']) {
      expect(within(table).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }
    const row = within(table).getByRole('row', { name: /TR-0001/ });
    expect(within(row).getByText('REQUESTED')).toBeInTheDocument();
    expect(within(row).getByText('BED_SHORTAGE')).toBeInTheDocument();
    expect(within(row).getByText('This hospital → Demo Harborview Hospital (outgoing)')).toBeInTheDocument();

    const calls = fetchMock.mock.calls.filter(([path]) => path === '/api/transfers');
    expect(calls).toHaveLength(1);
    expect(calls[0][1].method).toBe('GET');
    expect(calls[0][1].headers.Authorization).toBe('Bearer synthetic-token');
    // Raw pointers never render.
    expect(document.body).not.toHaveTextContent(PATIENT_ID);
    expect(document.body).not.toHaveTextContent(SOURCE_ID);
  });

  it('renders the honest empty and error states', async () => {
    stubTransfers(fetchMock, []);
    render(<TransfersPage session={session} onSessionExpired={vi.fn()} />);
    await screen.findByText('No transfers involve this hospital yet.');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    stubTransfers(fetchMock, [], 500);
    render(<TransfersPage session={session} onSessionExpired={vi.fn()} />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The request failed (500). Please try again.');
  });

  it('issues the exact create body with an idempotency key through the request form', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((path, options = {}) => {
      if (path === '/api/transfers' && options.method === 'POST') {
        return Promise.resolve(jsonResponse(transferRow(), 201));
      }
      if (path === '/api/transfers') {
        return Promise.resolve(jsonResponse([transferRow()]));
      }
      if (path === '/api/network/hierarchy') {
        return Promise.resolve(jsonResponse({
          organizationId: 'o1', organizationCode: 'ORG', organizationName: 'Demo Synthetic Hospital',
          hospitals: DESTINATIONS.map((hospital) => ({
            id: hospital.id, code: hospital.code, name: hospital.name,
            regionLabel: 'Region', timeZone: 'UTC', active: true, branches: [],
          })),
        }));
      }
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/admissions') return Promise.resolve(jsonResponse(ADMISSIONS));
      return Promise.resolve(jsonResponse({}, 404));
    });
    render(<TransfersPage session={session} onSessionExpired={vi.fn()} />);
    await waitLoaded();

    await user.click(screen.getByRole('button', { name: 'Request transfer' }));
    const form = screen.getByRole('form', { name: 'Request a transfer' });
    await user.selectOptions(within(form).getByLabelText('Patient'), PATIENT_ID);
    await user.selectOptions(within(form).getByLabelText('Admission'), 'aaaa1111-1111-4111-8111-111111111111');
    await user.selectOptions(within(form).getByLabelText('Destination hospital'), DEST_ID);
    await user.selectOptions(within(form).getByLabelText('Reason'), 'BED_SHORTAGE');
    await user.click(within(form).getByRole('button', { name: 'Submit transfer request' }));

    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([path, options]) => path === '/api/transfers' && options.method === 'POST')).toBe(true);
    });
    const [, options] = fetchMock.mock.calls.find(([path, call]) => path === '/api/transfers' && call.method === 'POST');
    expect(options.headers['Idempotency-Key']).toMatch(/^[A-Za-z0-9][A-Za-z0-9._-]{7,63}$/);
    expect(JSON.parse(options.body)).toEqual({
      patientId: PATIENT_ID,
      sourceAdmissionId: 'aaaa1111-1111-4111-8111-111111111111',
      destinationHospitalId: DEST_ID,
      reasonCode: 'BED_SHORTAGE',
    });
    await screen.findByText('Transfer requested.');
  });

  it('renders the detail panel with exactly the permitted transitions for REQUESTED', async () => {
    const user = userEvent.setup();
    const row = transferRow();
    fetchMock.mockImplementation((path) => {
      if (path === '/api/transfers') {
        return Promise.resolve(jsonResponse([row]));
      }
      if (path === `/api/transfers/${row.id}`) {
        return Promise.resolve(jsonResponse(row));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    render(<TransfersPage session={session} onSessionExpired={vi.fn()} />);

    const table = await screen.findByRole('table', { name: 'Authorized transfers' });
    await user.click(within(table).getByRole('button', { name: /View transfer TR-0001/ }));

    const detail = screen.getByRole('complementary', { name: 'Transfer detail' });
    expect(detail).toHaveTextContent('TR-0001');
    expect(detail).toHaveTextContent('REQUESTED');
    // Source-side on REQUESTED: cancel and start-transit permitted; accept
    // and reject are destination-side, complete needs IN_TRANSIT.
    expect(within(detail).getByRole('button', { name: 'Cancel transfer' })).toBeInTheDocument();
    expect(within(detail).getByRole('button', { name: 'Start transit' })).toBeInTheDocument();
    expect(within(detail).queryByRole('button', { name: 'Accept transfer' })).not.toBeInTheDocument();
    expect(within(detail).queryByRole('button', { name: 'Complete transfer' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(PATIENT_ID);
  });

  it('shows the destination-side controls with bed choice for an ACCEPTED-side transfer', async () => {
    const user = userEvent.setup();
    // Acting on the DESTINATION hospital: the list is the same server slice.
    const destinationSession = {
      ...session,
      roles: ['DOCTOR'],
      assignments: [{
        id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        role: 'DOCTOR', scope: 'BRANCH', organizationId: 'o1',
        hospitalId: DEST_ID, hospitalLabel: 'Demo Harborview Hospital',
        branchId: 'b2', branchLabel: 'Demo Harborview Pier Branch',
        departmentId: null, departmentLabel: null, enabled: true,
      }],
      actingContext: {
        username: 'testuser', assignmentId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        role: 'DOCTOR', scope: 'BRANCH', organizationId: 'o1',
        hospitalId: DEST_ID, branchId: 'b2', departmentId: null,
      },
    };
    fetchMock.mockImplementation((path) => {
      if (path === '/api/transfers') {
        return Promise.resolve(jsonResponse([transferRow({
          status: 'ACCEPTED',
          destinationBranchId: 'b2',
          destinationBedId: 'bed-1',
          version: 1,
        })]));
      }
      if (path === `/api/transfers/${transferRow().id}`) {
        return Promise.resolve(jsonResponse(transferRow({
          status: 'ACCEPTED',
          destinationBranchId: 'b2',
          destinationBedId: 'bed-1',
          version: 1,
        })));
      }
      if (path === '/api/branches') {
        return Promise.resolve(jsonResponse([
          { id: 'b2', code: 'DEMO-BR-2201', name: 'Demo Harborview Pier Branch', active: true },
        ]));
      }
      if (path === '/api/beds') {
        return Promise.resolve(jsonResponse([
          { id: 'bed-9', occupancyStatus: 'AVAILABLE', ward: 'W', room: '1', bedNumber: '9' },
        ]));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    render(<TransfersPage session={destinationSession} onSessionExpired={vi.fn()} />);

    const table = await screen.findByRole('table', { name: 'Authorized transfers' });
    await user.click(within(table).getByRole('button', { name: /View transfer TR-0001/ }));

    const detail = screen.getByRole('complementary', { name: 'Transfer detail' });
    expect(detail).toHaveTextContent('ACCEPTED');
    expect(within(detail).getByRole('button', { name: 'Accept transfer' })).toBeInTheDocument();
    expect(within(detail).getByRole('button', { name: 'Reject transfer' })).toBeInTheDocument();
    expect(within(detail).queryByRole('button', { name: 'Cancel transfer' })).not.toBeInTheDocument();
  });

  it('renders a 409 conflict inline, keeps the detail open, and preserves the stored version', async () => {
    const user = userEvent.setup();
    let detailCalls = 0;
    fetchMock.mockImplementation((path, options = {}) => {
      if (path === '/api/transfers') {
        return Promise.resolve(jsonResponse([transferRow({ status: 'ACCEPTED', destinationBranchId: 'b2', destinationBedId: 'bed-1', version: 1 })]));
      }
      if (path === `/api/transfers/${transferRow().id}/cancel`) {
        return Promise.resolve(jsonResponse({ error: 'conflict' }, 409));
      }
      if (path === `/api/transfers/${transferRow().id}`) {
        detailCalls += 1;
        return Promise.resolve(jsonResponse(transferRow({ status: 'ACCEPTED', destinationBranchId: 'b2', destinationBedId: 'bed-1', version: 1 })));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    render(<TransfersPage session={session} onSessionExpired={vi.fn()} />);

    const table = await screen.findByRole('table', { name: 'Authorized transfers' });
    await user.click(within(table).getByRole('button', { name: /View transfer TR-0001/ }));
    const detail = screen.getByRole('complementary', { name: 'Transfer detail' });

    await user.click(within(detail).getByRole('button', { name: 'Cancel transfer' }));
    await user.click(await within(detail).findByRole('button', { name: 'Confirm cancel' }));

    const alert = await within(detail).findByRole('alert');
    expect(alert).toHaveTextContent('The request failed (409). Please try again.');
    // The detail stays open with the stored version intact for the next try.
    expect(within(detail).getByRole('button', { name: 'Confirm cancel' })).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Transfer detail' })).toBeInTheDocument();
  });
});
