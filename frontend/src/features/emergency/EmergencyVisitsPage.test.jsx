import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fireEvent } from '@testing-library/react';
import EmergencyVisitsPage, { emergencyDisplayState } from './EmergencyVisitsPage.jsx';

const PATIENT_A = {
  id: '11111111-1111-4111-8111-111111111111',
  medicalRecordNumber: 'MRN-1001',
  fullName: 'Amal Hassan',
  dateOfBirth: '1990-05-14',
  sex: 'female',
  phone: '',
  email: '',
  nationalId: '',
  address: '',
  active: true,
};

const PATIENT_B = {
  id: '22222222-2222-4222-8222-222222222222',
  medicalRecordNumber: 'MRN-1002',
  fullName: 'Omar Diab',
  dateOfBirth: '1985-11-02',
  sex: 'male',
  phone: '',
  email: '',
  nationalId: '',
  address: '',
  active: false,
};

const PATIENTS = [PATIENT_A, PATIENT_B];

// docs/plan2.md Task 3 DTO contract: id/patientId/arrivalAt/triageLevel/
// chiefComplaint/status — no persistence metadata anywhere. The triage label
// is a neutral demo value with no clinical meaning.
const VISIT_WAITING = {
  id: '99999999-9999-4999-8999-999999999911',
  patientId: PATIENT_A.id,
  arrivalAt: '2031-01-01T09:15',
  triageLevel: '3',
  chiefComplaint: 'Synthetic waiting complaint',
  status: 'WAITING',
};

const VISIT_IN_TREATMENT = {
  id: '99999999-9999-4999-8999-999999999912',
  patientId: PATIENT_B.id,
  arrivalAt: '2031-01-02T11:30',
  triageLevel: '5',
  chiefComplaint: 'Synthetic in-treatment complaint',
  status: 'IN_TREATMENT',
};

const VISIT_CLOSED = {
  id: '99999999-9999-4999-8999-999999999913',
  patientId: PATIENT_A.id,
  arrivalAt: '2031-01-03T07:45',
  triageLevel: '1',
  chiefComplaint: 'Synthetic closed complaint',
  status: 'CLOSED',
};

// A list row whose patient reference cannot be resolved against the loaded
// records. The list must render an honest placeholder, never the raw value.
const VISIT_UNKNOWN_REF = {
  id: '99999999-9999-4999-8999-999999999914',
  patientId: 'raw unresolved reference value',
  arrivalAt: '2031-01-04T18:00',
  triageLevel: '2',
  chiefComplaint: 'Synthetic unresolved reference',
  status: 'WAITING',
};

const VISITS_INITIAL = [VISIT_WAITING, VISIT_IN_TREATMENT, VISIT_CLOSED];

const VISITS_AFTER_CREATE = [
  VISIT_WAITING,
  VISIT_IN_TREATMENT,
  VISIT_CLOSED,
  {
    id: '99999999-9999-4999-8999-999999999915',
    patientId: PATIENT_A.id,
    arrivalAt: '2031-02-01T08:00',
    triageLevel: '4',
    chiefComplaint: 'Synthetic new complaint',
    status: 'WAITING',
  },
];

// Task 8 branch fixtures: two acting contexts bound to different branches.
// The branch-B visit carries its own unique complaint so a stale branch-A
// row can never be confused with branch-B content.
const VISIT_BRANCH_B = {
  id: '99999999-9999-4999-8999-999999999921',
  patientId: PATIENT_B.id,
  arrivalAt: '2031-03-01T12:00',
  triageLevel: '4',
  chiefComplaint: 'Synthetic west-branch complaint',
  status: 'WAITING',
};

function actingSessionFor(token, assignmentId, branchId) {
  return {
    token,
    username: 'testuser',
    roles: ['ADMIN'],
    assignments: [{
      id: assignmentId,
      role: 'ADMIN',
      scope: 'ORGANIZATION',
      organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      organizationLabel: 'Main Hospital Group',
      branchId,
      branchLabel: branchId === 'branch-b' ? 'West Clinic' : 'East Clinic',
      departmentId: null,
      departmentLabel: null,
      enabled: true,
    }],
    actingContext: {
      username: 'testuser',
      assignmentId,
      role: 'ADMIN',
      scope: 'ORGANIZATION',
      organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      branchId,
      departmentId: null,
    },
  };
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function sessionFor(roles) {
  return { token: 'synthetic-token', username: 'testuser', roles };
}

function renderEmergency(roles = ['ADMIN']) {
  const onSessionExpired = vi.fn();
  const view = render(
    <EmergencyVisitsPage session={sessionFor(roles)} onSessionExpired={onSessionExpired} />
  );
  return { onSessionExpired, ...view };
}

// Router over the two consumed endpoint families. `state` is read live so
// tests can switch payloads and outcomes between calls.
function stubBackend(fetchMock, state) {
  fetchMock.mockImplementation((path, options = {}) => {
    const method = options.method ?? 'GET';
    if (path === '/api/patients') {
      return Promise.resolve(jsonResponse(PATIENTS, state.patientsStatus ?? 200));
    }
    if (path === '/api/emergency-visits') {
      if (method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        if (state.postResponse) return Promise.resolve(state.postResponse);
        return Promise.resolve(jsonResponse(VISITS_AFTER_CREATE[3]));
      }
      return Promise.resolve(jsonResponse(state.visitsList, state.visitsStatus ?? 200));
    }
    if (/^\/api\/emergency-visits\/[^/]+\/status$/.test(path)) {
      state.putCalls.push({ path, body: JSON.parse(options.body) });
      if (state.putResponse) return Promise.resolve(state.putResponse);
      const target = JSON.parse(options.body).status;
      return Promise.resolve(jsonResponse({ ...VISIT_WAITING, status: target }));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
}

// Records the text content of every committed render pass, in commit order,
// into `frames`. The inline ref is invoked by React during each commit phase
// — before passive effects run — so whatever the screen painted for a given
// commit is captured exactly as the browser would have received it. This is
// what makes the render-phase boundary observable in integration without
// touching React internals.
function CommitLog({ frames, children }) {
  return (
    <div ref={(node) => { if (node) frames.push(node.textContent); }}>
      {children}
    </div>
  );
}

function visitsTable() {
  return screen.queryByRole('table', { name: 'Registered emergency visits' });
}

describe('EmergencyVisitsPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('loads the visits list with resolved names, triage and status badges, and honest placeholders', async () => {
    const state = { postCalls: [], putCalls: [], visitsList: [...VISITS_INITIAL, VISIT_UNKNOWN_REF] };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderEmergency(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Registered emergency visits' });
    // Two rows reference the same patient, so rows are identified by their
    // unique complaint text, not the patient name alone.
    const waitingRow = within(table).getByRole('row', { name: /Synthetic waiting complaint/ });
    expect(within(waitingRow).getByText('Amal Hassan')).toBeInTheDocument();
    expect(within(waitingRow).getByText('WAITING')).toBeInTheDocument();
    expect(within(waitingRow).getByText('2031-01-01T09:15')).toBeInTheDocument();
    expect(within(waitingRow).getByText('3')).toBeInTheDocument();

    const treatedRow = within(table).getByRole('row', { name: /Omar Diab/ });
    expect(within(treatedRow).getByText('IN_TREATMENT')).toBeInTheDocument();
    expect(within(treatedRow).getByText('5')).toBeInTheDocument();

    const unknownRow = within(table).getByRole('row', { name: /Synthetic unresolved reference/ });
    expect(within(unknownRow).getByText('Unknown record')).toBeInTheDocument();
    expect(table).not.toHaveTextContent('raw unresolved reference value');

    // The non-clinical boundary is demonstrably stated on the screen.
    expect(screen.getByRole('region', { name: 'Emergency Visits screen' }))
      .toHaveTextContent(/no clinical meaning/i);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows the empty state when no emergency visits exist', async () => {
    const state = { postCalls: [], putCalls: [], visitsList: [] };
    stubBackend(fetchMock, state);
    renderEmergency(['RECEPTIONIST']);

    // Wait for the empty panel heading specifically: the transient loading
    // notice also carries role="status" and must not satisfy this test.
    const empty = await screen.findByText('No emergency visits registered');
    expect(empty).toBeInTheDocument();
    expect(visitsTable()).not.toBeInTheDocument();
  });

  it('shows an honest error when the visits list fails to load', async () => {
    const state = { postCalls: [], putCalls: [], visitsList: [], visitsStatus: 500 };
    stubBackend(fetchMock, state);
    renderEmergency(['ADMIN']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/500/);
    expect(visitsTable()).not.toBeInTheDocument();
  });

  it('shows the permission message when the visits list is refused (403)', async () => {
    const state = { postCalls: [], putCalls: [], visitsList: [], visitsStatus: 403 };
    stubBackend(fetchMock, state);
    renderEmergency(['ADMIN']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');
    expect(visitsTable()).not.toBeInTheDocument();
  });

  it('labels the triage select demonstrably as a non-clinical demo value', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], visitsList: VISITS_INITIAL };
    stubBackend(fetchMock, state);
    renderEmergency(['NURSE']);
    await screen.findByRole('table', { name: 'Registered emergency visits' });

    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    const triageSelect = await screen.findByLabelText(/Triage label \(demo 1–5, no clinical meaning\)/);
    expect(triageSelect).toBeInTheDocument();
    const options = within(triageSelect).getAllByRole('option');
    expect(options.map((option) => option.value)).toEqual(['', '1', '2', '3', '4', '5']);
    expect(screen.getByText(/Neutral demo label \(1–5\) with no clinical meaning/)).toBeInTheDocument();
  });

  it('registers a visit from the selected records on the typed contract and refreshes the list', async () => {
    const user = userEvent.setup();
    let resolvePost;
    const state = { postCalls: [], putCalls: [], visitsList: VISITS_INITIAL };
    stubBackend(fetchMock, state);
    fetchMock.mockImplementation((path, options = {}) => {
      const method = options.method ?? 'GET';
      if (path === '/api/emergency-visits' && method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        return new Promise((resolve) => { resolvePost = resolve; });
      }
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/emergency-visits') return Promise.resolve(jsonResponse(state.visitsList));
      return Promise.resolve(jsonResponse({}, 404));
    });

    const { onSessionExpired } = renderEmergency(['NURSE']);
    await screen.findByRole('table', { name: 'Registered emergency visits' });
    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    const patientSelect = await screen.findByLabelText('Patient');
    expect(within(patientSelect).getAllByRole('option')).toHaveLength(3); // placeholder + two records
    await user.selectOptions(patientSelect, PATIENT_A.id);
    fireEvent.change(screen.getByLabelText('Arrival date and time'), {
      target: { value: '2031-02-01T08:00' },
    });
    await user.selectOptions(screen.getByLabelText(/Triage label/), '4');
    await user.type(screen.getByLabelText('Chief complaint'), 'Synthetic new complaint');

    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    // While the create request is in flight the submit is disabled.
    const pendingButton = await screen.findByRole('button', { name: 'Registering…' });
    expect(pendingButton).toBeDisabled();

    state.visitsList = VISITS_AFTER_CREATE;
    resolvePost(jsonResponse(VISITS_AFTER_CREATE[3]));

    // List refresh: the new visit appears through a fresh GET.
    const table = await screen.findByRole('table', { name: 'Registered emergency visits' });
    await waitFor(() => {
      expect(within(table).getAllByRole('row', { name: /Synthetic new complaint/ })).toHaveLength(1);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/emergency visit registered/i);

    expect(state.postCalls).toHaveLength(1);
    // The body mirrors CreateEmergencyVisitRequest exactly — four fields, no
    // client status anywhere.
    expect(Object.keys(state.postCalls[0]).sort())
      .toEqual(['arrivalAt', 'chiefComplaint', 'patientId', 'triageLevel']);
    expect(state.postCalls[0]).toEqual({
      patientId: PATIENT_A.id,
      arrivalAt: '2031-02-01T08:00',
      triageLevel: '4',
      chiefComplaint: 'Synthetic new complaint',
    });
    const postCall = fetchMock.mock.calls.find(([path, options]) => path === '/api/emergency-visits' && options?.method === 'POST');
    expect(postCall[1].headers.Authorization).toBe('Bearer synthetic-token');
    expect(postCall[1].headers['Content-Type']).toBe('application/json');
    const visitGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/emergency-visits' && (options?.method ?? 'GET') === 'GET');
    expect(visitGetCalls).toHaveLength(2);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('validates before submitting: an incomplete form shows an inline error and never posts', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], visitsList: VISITS_INITIAL };
    stubBackend(fetchMock, state);
    renderEmergency(['RECEPTIONIST']);
    await screen.findByRole('table', { name: 'Registered emergency visits' });
    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    await screen.findByLabelText('Patient');
    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/select a patient/i);
    expect(state.postCalls).toHaveLength(0);
  });

  it('cancels the form and returns to the unchanged list', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], visitsList: VISITS_INITIAL };
    stubBackend(fetchMock, state);
    renderEmergency(['NURSE']);
    await screen.findByRole('table', { name: 'Registered emergency visits' });

    await user.click(screen.getByRole('button', { name: 'Register visit' }));
    expect(await screen.findByLabelText('Chief complaint')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByLabelText('Patient')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register visit' })).toBeInTheDocument();
    expect(state.postCalls).toHaveLength(0);
  });

  it('shows the server error inline and keeps every entered value when a selected reference is stale (404)', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      visitsList: VISITS_INITIAL,
      postResponse: jsonResponse({ message: 'Patient not found' }, 404),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderEmergency(['ADMIN']);
    await screen.findByRole('table', { name: 'Registered emergency visits' });
    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    await user.selectOptions(await screen.findByLabelText('Patient'), PATIENT_A.id);
    fireEvent.change(screen.getByLabelText('Arrival date and time'), {
      target: { value: '2031-02-02T14:15' },
    });
    await user.selectOptions(screen.getByLabelText(/Triage label/), '2');
    await user.type(screen.getByLabelText('Chief complaint'), 'Synthetic stale reference');

    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/404/);

    // No data loss: every selection survives the failed submit.
    expect(screen.getByLabelText('Patient')).toHaveValue(PATIENT_A.id);
    expect(screen.getByLabelText('Arrival date and time')).toHaveValue('2031-02-02T14:15');
    expect(screen.getByLabelText(/Triage label/)).toHaveValue('2');
    expect(screen.getByLabelText('Chief complaint')).toHaveValue('Synthetic stale reference');

    // A failed create never refreshes the list.
    const visitGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/emergency-visits' && (options?.method ?? 'GET') === 'GET');
    expect(visitGetCalls).toHaveLength(1);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('returns to the shell on session expiry instead of raising a local error', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      visitsList: VISITS_INITIAL,
      postResponse: jsonResponse({ message: 'authentication required' }, 401),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderEmergency(['ADMIN']);
    await screen.findByRole('table', { name: 'Registered emergency visits' });
    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    await user.selectOptions(await screen.findByLabelText('Patient'), PATIENT_A.id);
    fireEvent.change(screen.getByLabelText('Arrival date and time'), {
      target: { value: '2031-03-01T11:00' },
    });
    await user.selectOptions(screen.getByLabelText(/Triage label/), '1');
    await user.type(screen.getByLabelText('Chief complaint'), 'Synthetic expired session');

    await user.click(screen.getByRole('button', { name: 'Register visit' }));

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('moves a waiting visit to IN_TREATMENT only after deliberate confirmation', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], visitsList: [VISIT_WAITING] };
    stubBackend(fetchMock, state);
    renderEmergency(['NURSE']);

    const table = await screen.findByRole('table', { name: 'Registered emergency visits' });
    const row = within(table).getByRole('row', { name: /Amal Hassan/ });

    await user.click(within(row).getByRole('button', { name: 'Start treatment' }));

    // Deliberate confirmation step before anything is sent.
    expect(within(row).getByRole('button', { name: 'Confirm start treatment' })).toBeEnabled();
    expect(within(row).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(state.putCalls).toHaveLength(0);

    await user.click(within(row).getByRole('button', { name: 'Cancel' }));
    expect(within(row).getByRole('button', { name: 'Start treatment' })).toBeInTheDocument();
    expect(state.putCalls).toHaveLength(0);

    state.visitsList = [{ ...VISIT_WAITING, status: 'IN_TREATMENT' }];
    await user.click(within(row).getByRole('button', { name: 'Start treatment' }));
    await user.click(within(row).getByRole('button', { name: 'Confirm start treatment' }));

    await waitFor(() => expect(state.putCalls).toHaveLength(1));
    expect(state.putCalls[0].path).toBe(`/api/emergency-visits/${VISIT_WAITING.id}/status`);
    expect(state.putCalls[0].body).toEqual({ status: 'IN_TREATMENT' });

    // The list refetches; the row shows its new state and now offers only
    // the close action.
    const refreshedTable = await screen.findByRole('table', { name: 'Registered emergency visits' });
    const refreshedRow = await within(refreshedTable).findByRole('row', { name: /Amal Hassan/ });
    await waitFor(() => expect(within(refreshedRow).getByText('IN_TREATMENT')).toBeInTheDocument());
    expect(within(refreshedRow).getByRole('button', { name: 'Close visit' })).toBeInTheDocument();
    expect(within(refreshedRow).queryByRole('button', { name: 'Start treatment' })).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/treatment started/i);
  });

  it('closes a waiting visit directly and offers no actions on a CLOSED row', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], visitsList: [VISIT_WAITING, VISIT_CLOSED] };
    stubBackend(fetchMock, state);
    renderEmergency(['RECEPTIONIST']);

    const table = await screen.findByRole('table', { name: 'Registered emergency visits' });
    // Both rows reference the same patient, so rows are identified by their
    // unique complaint text.
    const waitingRow = within(table).getByRole('row', { name: /Synthetic waiting complaint/ });
    expect(within(waitingRow).getByText('Amal Hassan')).toBeInTheDocument();
    const closedRow = within(table).getByRole('row', { name: /Synthetic closed complaint/ });

    // CLOSED is terminal: no transition actions at all.
    expect(within(closedRow).queryByRole('button')).not.toBeInTheDocument();

    state.visitsList = [
      { ...VISIT_WAITING, status: 'CLOSED' },
      VISIT_CLOSED,
    ];
    await user.click(within(waitingRow).getByRole('button', { name: 'Close visit' }));
    await user.click(within(waitingRow).getByRole('button', { name: 'Confirm close' }));

    await waitFor(() => expect(state.putCalls).toHaveLength(1));
    expect(state.putCalls[0].body).toEqual({ status: 'CLOSED' });
    expect(screen.getByRole('status')).toHaveTextContent(/visit closed/i);
  });

  it('shows the conflict message when the server refuses the transition (409) and keeps the row state', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      visitsList: [VISIT_WAITING],
      putResponse: jsonResponse(
        { message: 'Emergency visit ' + VISIT_WAITING.id + ' is CLOSED: no further transitions are defined' },
        409
      ),
    };
    stubBackend(fetchMock, state);
    renderEmergency(['DOCTOR']);

    const table = await screen.findByRole('table', { name: 'Registered emergency visits' });
    const row = within(table).getByRole('row', { name: /Amal Hassan/ });

    await user.click(within(row).getByRole('button', { name: 'Close visit' }));
    await user.click(within(row).getByRole('button', { name: 'Confirm close' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/409/);

    // The row keeps its state; no list refresh was issued.
    expect(within(row).getByText('WAITING')).toBeInTheDocument();
    const visitGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/emergency-visits' && (options?.method ?? 'GET') === 'GET');
    expect(visitGetCalls).toHaveLength(1);
  });

  it('reloads the branch-scoped list under the new context-bound token after a context switch and never renders the previous branch\'s rows', async () => {
    let resolveBranchBVisits;
    fetchMock.mockImplementation((path, options = {}) => {
      const token = options.headers?.Authorization;
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/emergency-visits' && (options.method ?? 'GET') === 'GET') {
        if (token === 'Bearer branch-b-token') {
          return new Promise((resolve) => { resolveBranchBVisits = resolve; });
        }
        return Promise.resolve(jsonResponse([VISIT_WAITING]));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    const onSessionExpired = vi.fn();
    const sessionA = actingSessionFor('branch-a-token', 'assign-a', 'branch-a');
    const sessionB = actingSessionFor('branch-b-token', 'assign-b', 'branch-b');

    const view = render(<EmergencyVisitsPage session={sessionA} onSessionExpired={onSessionExpired} />);
    const tableA = await screen.findByRole('table', { name: 'Registered emergency visits' });
    expect(within(tableA).getByText('Synthetic waiting complaint')).toBeInTheDocument();

    // The shell swapped in the complete switched session (new context-bound
    // token, new branch). The screen reloads for the acting context and
    // drops the previous branch's rows: while the new request is pending no
    // stale branch-A row may remain, and after it resolves only branch-B
    // content is shown.
    view.rerender(<EmergencyVisitsPage session={sessionB} onSessionExpired={onSessionExpired} />);

    expect(screen.queryByRole('table', { name: 'Registered emergency visits' })).not.toBeInTheDocument();
    expect(screen.queryByText('Synthetic waiting complaint')).not.toBeInTheDocument();

    resolveBranchBVisits(jsonResponse([VISIT_BRANCH_B]));
    const tableB = await screen.findByRole('table', { name: 'Registered emergency visits' });
    expect(within(tableB).getByText('Synthetic west-branch complaint')).toBeInTheDocument();
    expect(tableB).not.toHaveTextContent('Synthetic waiting complaint');

    const visitGets = fetchMock.mock.calls.filter(
      ([path, options]) => path === '/api/emergency-visits' && (options?.method ?? 'GET') === 'GET'
    );
    expect(visitGets).toHaveLength(2);
    expect(visitGets[0][1].headers.Authorization).toBe('Bearer branch-a-token');
    expect(visitGets[1][1].headers.Authorization).toBe('Bearer branch-b-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('offers register and transition actions to every clinical-administrative role', async () => {
    const state = { postCalls: [], putCalls: [], visitsList: [VISIT_WAITING] };
    stubBackend(fetchMock, state);

    for (const role of ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']) {
      const view = renderEmergency([role]);
      const table = await screen.findByRole('table', { name: 'Registered emergency visits' });
      expect(screen.getByRole('button', { name: 'Register visit' })).toBeInTheDocument();
      expect(within(table).getByRole('button', { name: 'Start treatment' })).toBeInTheDocument();
      expect(within(table).getByRole('button', { name: 'Close visit' })).toBeInTheDocument();
      view.unmount();
    }
  });

  // ---- Task 8 review repair (MEDICORE-PLAN3-TASK8-REVIEW-REPAIR-065) ----
  // Render-phase contract. Loaded branch-owned data is tagged with the
  // actingContextKey it resolved under, and emergencyDisplayState — the pure
  // selector the page's render calls on every pass — exposes it only while
  // that tag equals the current contextKey. The selector pins are
  // deterministic by construction (they prove the decision made during
  // render, independent of effect timing); the commit-log integration test
  // below proves the same boundary on actually committed DOM frames.
  describe('emergencyDisplayState render contract', () => {
    const KEY_A = 'assign-a:branch-a';
    const KEY_B = 'assign-b:branch-b';
    const loadedUnderA = {
      contextKey: KEY_A,
      status: 'ready',
      loadError: '',
      patients: [PATIENT_A],
      visits: [VISIT_WAITING],
    };

    it('exposes data loaded under context A while A is the current context', () => {
      const display = emergencyDisplayState({ contextKey: KEY_A, loaded: loadedUnderA });
      expect(display).toBe(loadedUnderA);
      expect(display.status).toBe('ready');
      expect(display.patients).toEqual([PATIENT_A]);
      expect(display.visits).toEqual([VISIT_WAITING]);
    });

    it('stops exposing that same data the moment the current context becomes B', () => {
      const display = emergencyDisplayState({ contextKey: KEY_B, loaded: loadedUnderA });
      expect(display).not.toBe(loadedUnderA);
      expect(display.patients).toEqual([]);
      expect(display.visits).toEqual([]);
    });

    it('shows the loading state for B — never the old rows and never a misleading empty state', () => {
      const display = emergencyDisplayState({ contextKey: KEY_B, loaded: loadedUnderA });
      // status 'loading' is what keeps the empty-state panel unrenderable:
      // that branch requires status 'ready'.
      expect(display.status).toBe('loading');
      expect(display.loadError).toBe('');
    });

    it('exposes only B rows once B has resolved under tag B', () => {
      const loadedUnderB = {
        contextKey: KEY_B,
        status: 'ready',
        loadError: '',
        patients: [PATIENT_B],
        visits: [VISIT_BRANCH_B],
      };
      const display = emergencyDisplayState({ contextKey: KEY_B, loaded: loadedUnderB });
      expect(display.status).toBe('ready');
      expect(display.patients).toEqual([PATIENT_B]);
      expect(display.visits).toEqual([VISIT_BRANCH_B]);
    });

    it('refuses a late A-tagged publication while B is current', () => {
      const lateA = {
        contextKey: KEY_A,
        status: 'ready',
        loadError: '',
        patients: [PATIENT_A],
        visits: [VISIT_WAITING],
      };
      const display = emergencyDisplayState({ contextKey: KEY_B, loaded: lateA });
      expect(display.visits).toEqual([]);
      expect(display.patients).toEqual([]);
      expect(display.status).toBe('loading');
    });

    it('does not carry a load error across a context boundary', () => {
      const failedA = {
        contextKey: KEY_A,
        status: 'ready',
        loadError: 'Emergency visits could not be loaded.',
        patients: [],
        visits: [],
      };
      const display = emergencyDisplayState({ contextKey: KEY_B, loaded: failedA });
      expect(display.loadError).toBe('');
      expect(display.status).toBe('loading');
    });
  });

  it('never commits the previous branch\'s rows to the DOM while the new context is pending', async () => {
    let resolveBranchBVisits;
    fetchMock.mockImplementation((path, options = {}) => {
      const token = options.headers?.Authorization;
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/emergency-visits' && (options.method ?? 'GET') === 'GET') {
        if (token === 'Bearer branch-b-token') {
          return new Promise((resolve) => { resolveBranchBVisits = resolve; });
        }
        return Promise.resolve(jsonResponse([VISIT_WAITING]));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    const onSessionExpired = vi.fn();
    const sessionA = actingSessionFor('branch-a-token', 'assign-a', 'branch-a');
    const sessionB = actingSessionFor('branch-b-token', 'assign-b', 'branch-b');
    const frames = [];

    const view = render(
      <CommitLog frames={frames}>
        <EmergencyVisitsPage session={sessionA} onSessionExpired={onSessionExpired} />
      </CommitLog>
    );
    const tableA = await screen.findByRole('table', { name: 'Registered emergency visits' });
    expect(within(tableA).getByText('Synthetic waiting complaint')).toBeInTheDocument();
    const commitsBeforeSwitch = frames.length;

    // The shell swaps in the switched session while branch B's request is
    // still pending. Every commit this rerender produces — including the
    // first one, which happens BEFORE the effect can run — must show the
    // loading state and must not contain branch-A rows. A rerender-time
    // assertion alone cannot see that first frame; the commit log can.
    view.rerender(
      <CommitLog frames={frames}>
        <EmergencyVisitsPage session={sessionB} onSessionExpired={onSessionExpired} />
      </CommitLog>
    );

    const framesAfterSwitch = frames.slice(commitsBeforeSwitch);
    expect(framesAfterSwitch.length).toBeGreaterThan(0);
    for (const frame of framesAfterSwitch) {
      expect(frame).toContain('Loading emergency visits');
      expect(frame).not.toContain('Synthetic waiting complaint');
    }
    expect(screen.queryByRole('table', { name: 'Registered emergency visits' })).not.toBeInTheDocument();

    // Only branch-B content appears once branch B resolves.
    resolveBranchBVisits(jsonResponse([VISIT_BRANCH_B]));
    const tableB = await screen.findByRole('table', { name: 'Registered emergency visits' });
    expect(within(tableB).getByText('Synthetic west-branch complaint')).toBeInTheDocument();
    expect(screen.queryByText('Synthetic waiting complaint')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('drops a context-A response that resolves late, after the switch to B', async () => {
    let resolveBranchAVisits;
    fetchMock.mockImplementation((path, options = {}) => {
      const token = options.headers?.Authorization;
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/emergency-visits' && (options.method ?? 'GET') === 'GET') {
        if (token === 'Bearer branch-a-token') {
          return new Promise((resolve) => { resolveBranchAVisits = resolve; });
        }
        return Promise.resolve(jsonResponse([VISIT_BRANCH_B]));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    const onSessionExpired = vi.fn();
    const sessionA = actingSessionFor('branch-a-token', 'assign-a', 'branch-a');
    const sessionB = actingSessionFor('branch-b-token', 'assign-b', 'branch-b');

    const view = render(<EmergencyVisitsPage session={sessionA} onSessionExpired={onSessionExpired} />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading emergency visits/i);

    // The switch happens while A is still in flight: the effect cleanup
    // cancels the pending A load. Its response then arrives late and must
    // never publish — not over B, and not at all.
    view.rerender(<EmergencyVisitsPage session={sessionB} onSessionExpired={onSessionExpired} />);
    resolveBranchAVisits(jsonResponse([VISIT_WAITING]));

    const tableB = await screen.findByRole('table', { name: 'Registered emergency visits' });
    expect(within(tableB).getByText('Synthetic west-branch complaint')).toBeInTheDocument();
    // By the time B's table is visible every pending microtask has flushed:
    // exactly one data row (branch B's — the name matcher ignores the
    // header row), and no branch-A content anywhere.
    expect(within(tableB).getAllByRole('row', { name: /Synthetic west-branch complaint/ })).toHaveLength(1);
    expect(screen.queryByText('Synthetic waiting complaint')).not.toBeInTheDocument();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
});
