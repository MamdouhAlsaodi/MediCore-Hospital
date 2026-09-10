import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fireEvent } from '@testing-library/react';
import EmergencyVisitsPage from './EmergencyVisitsPage.jsx';

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
});
