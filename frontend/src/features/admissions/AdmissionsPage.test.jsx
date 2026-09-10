import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdmissionsPage from './AdmissionsPage.jsx';
import PatientDetailPage from '../patients/PatientDetailPage.jsx';

const PATIENT_A = {
  id: '11111111-1111-4111-8111-111111111111',
  medicalRecordNumber: 'MRN-1001',
  fullName: 'Amal Hassan',
  dateOfBirth: '1990-05-14',
  sex: 'female',
  phone: '+20 100 000 0001',
  email: 'amal.hassan@example.com',
  nationalId: 'NID-SYNTHETIC-001',
  address: '1 Example Street, Test City',
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

// docs/plan2.md Task 2 DTO contract: id/patientId/admittedAt/dischargedAt/
// reason/status — no persistence metadata anywhere.
const ADMISSION_ADMITTED = {
  id: '99999999-9999-4999-8999-999999999901',
  patientId: PATIENT_A.id,
  admittedAt: '2031-01-01T08:15:30',
  dischargedAt: null,
  reason: 'Synthetic observation stay',
  status: 'ADMITTED',
};

const ADMISSION_DISCHARGED = {
  id: '99999999-9999-4999-8999-999999999902',
  patientId: PATIENT_B.id,
  admittedAt: '2031-02-01T10:00',
  dischargedAt: '2031-02-05T09:30',
  reason: 'Synthetic completed stay',
  status: 'DISCHARGED',
};

// A list row whose patient reference cannot be resolved against the loaded
// records. The list must render an honest placeholder, never the raw value.
const ADMISSION_UNKNOWN_REF = {
  id: '99999999-9999-4999-8999-999999999903',
  patientId: 'raw unresolved reference value',
  admittedAt: '2031-03-01T07:00',
  dischargedAt: null,
  reason: 'Synthetic unresolved reference',
  status: 'ADMITTED',
};

const ADMISSIONS_INITIAL = [ADMISSION_ADMITTED, ADMISSION_DISCHARGED];

const ADMISSIONS_AFTER_DISCHARGE = [
  { ...ADMISSION_ADMITTED, dischargedAt: '2031-01-05T12:00', status: 'DISCHARGED' },
  ADMISSION_DISCHARGED,
];

const ADMISSIONS_AFTER_CREATE = [
  ADMISSION_ADMITTED,
  ADMISSION_DISCHARGED,
  {
    id: '99999999-9999-4999-8999-999999999904',
    patientId: PATIENT_A.id,
    admittedAt: '2031-04-01T09:30',
    dischargedAt: null,
    reason: 'Synthetic new admission',
    status: 'ADMITTED',
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

function renderAdmissions(roles = ['ADMIN']) {
  const onSessionExpired = vi.fn();
  const view = render(
    <AdmissionsPage session={sessionFor(roles)} onSessionExpired={onSessionExpired} />
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
    if (path === '/api/admissions') {
      if (method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        if (state.postResponse) return Promise.resolve(state.postResponse);
        return Promise.resolve(jsonResponse(ADMISSION_ADMITTED));
      }
      return Promise.resolve(jsonResponse(state.admissionsList, state.admissionsStatus ?? 200));
    }
    if (/^\/api\/admissions\/[^/]+\/status$/.test(path)) {
      state.putCalls.push({ path, body: JSON.parse(options.body) });
      if (state.putResponse) return Promise.resolve(state.putResponse);
      return Promise.resolve(jsonResponse(
        state.dischargedAdmission ?? { ...ADMISSION_ADMITTED, dischargedAt: '2031-01-05T12:00', status: 'DISCHARGED' }
      ));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
}

async function openRegisterForm(user) {
  await user.click(screen.getByRole('button', { name: 'Register admission' }));
  return screen.findByLabelText('Patient');
}

function admissionGets() {
  return screen.queryByRole('table', { name: 'Registered admissions' });
}

describe('AdmissionsPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('loads the admissions list with resolved patient names, status badges, and honest placeholders', async () => {
    const state = { postCalls: [], putCalls: [], admissionsList: [...ADMISSIONS_INITIAL, ADMISSION_UNKNOWN_REF] };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAdmissions(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const admittedRow = within(table).getByRole('row', { name: /Amal Hassan/ });
    expect(within(admittedRow).getByText('ADMITTED')).toBeInTheDocument();
    expect(within(admittedRow).getByText('2031-01-01T08:15:30')).toBeInTheDocument();
    expect(within(admittedRow).getByText('Synthetic observation stay')).toBeInTheDocument();
    // dischargedAt is unset on an open admission and rendered as an em dash.
    expect(within(admittedRow).getByText('—')).toBeInTheDocument();

    const dischargedRow = within(table).getByRole('row', { name: /Omar Diab/ });
    expect(within(dischargedRow).getByText('DISCHARGED')).toBeInTheDocument();
    expect(within(dischargedRow).getByText('2031-02-05T09:30')).toBeInTheDocument();

    const unknownRow = within(table).getByRole('row', { name: /Synthetic unresolved reference/ });
    expect(within(unknownRow).getByText('Unknown record')).toBeInTheDocument();
    expect(table).not.toHaveTextContent('raw unresolved reference value');

    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows the empty state when no admissions exist', async () => {
    const state = { postCalls: [], putCalls: [], admissionsList: [] };
    stubBackend(fetchMock, state);
    renderAdmissions(['RECEPTIONIST']);

    // Wait for the empty panel heading specifically: the transient loading
    // notice also carries role="status" and must not satisfy this test.
    const empty = await screen.findByText('No admissions registered');
    expect(empty).toBeInTheDocument();
    expect(admissionGets()).not.toBeInTheDocument();
  });

  it('shows an honest error when the admissions list fails to load', async () => {
    const state = { postCalls: [], putCalls: [], admissionsList: [], admissionsStatus: 500 };
    stubBackend(fetchMock, state);
    renderAdmissions(['ADMIN']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/500/);
    expect(admissionGets()).not.toBeInTheDocument();
  });

  it('shows the permission message when the admissions list is refused (403)', async () => {
    const state = { postCalls: [], putCalls: [], admissionsList: [], admissionsStatus: 403 };
    stubBackend(fetchMock, state);
    renderAdmissions(['ADMIN']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');
    expect(admissionGets()).not.toBeInTheDocument();
  });

  it('registers an admission from the selected records on the typed contract and refreshes the list', async () => {
    const user = userEvent.setup();
    let resolvePost;
    const state = { postCalls: [], putCalls: [], admissionsList: ADMISSIONS_INITIAL };
    stubBackend(fetchMock, state);
    fetchMock.mockImplementation((path, options = {}) => {
      const method = options.method ?? 'GET';
      if (path === '/api/admissions' && method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        return new Promise((resolve) => { resolvePost = resolve; });
      }
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/admissions') return Promise.resolve(jsonResponse(state.admissionsList));
      return Promise.resolve(jsonResponse({}, 404));
    });

    const { onSessionExpired } = renderAdmissions(['NURSE']);
    await screen.findByRole('table', { name: 'Registered admissions' });
    await openRegisterForm(user);

    const patientSelect = screen.getByLabelText('Patient');
    expect(within(patientSelect).getAllByRole('option')).toHaveLength(3); // placeholder + two records
    await user.selectOptions(patientSelect, PATIENT_A.id);
    fireEvent.change(screen.getByLabelText('Admitted at'), {
      target: { value: '2031-04-01T09:30' },
    });
    await user.type(screen.getByLabelText('Reason'), 'Synthetic new admission');

    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    // While the create request is in flight the submit is disabled.
    const pendingButton = await screen.findByRole('button', { name: 'Registering…' });
    expect(pendingButton).toBeDisabled();

    state.admissionsList = ADMISSIONS_AFTER_CREATE;
    resolvePost(jsonResponse(ADMISSIONS_AFTER_CREATE[2]));

    // List refresh: the new admission appears through a fresh GET.
    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    await waitFor(() => {
      expect(within(table).getAllByRole('row', { name: /Synthetic new admission/ })).toHaveLength(1);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/admission registered/i);

    expect(state.postCalls).toHaveLength(1);
    // The body mirrors CreateAdmissionRequest exactly — three fields, no
    // client status or dischargedAt anywhere.
    expect(Object.keys(state.postCalls[0]).sort()).toEqual(['admittedAt', 'patientId', 'reason']);
    expect(state.postCalls[0]).toEqual({
      patientId: PATIENT_A.id,
      admittedAt: '2031-04-01T09:30',
      reason: 'Synthetic new admission',
    });
    const postCall = fetchMock.mock.calls.find(([path, options]) => path === '/api/admissions' && options?.method === 'POST');
    expect(postCall[1].headers.Authorization).toBe('Bearer synthetic-token');
    expect(postCall[1].headers['Content-Type']).toBe('application/json');
    const admissionGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/admissions' && (options?.method ?? 'GET') === 'GET');
    expect(admissionGetCalls).toHaveLength(2);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('validates before submitting: an incomplete form shows an inline error and never posts', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], admissionsList: ADMISSIONS_INITIAL };
    stubBackend(fetchMock, state);
    renderAdmissions(['RECEPTIONIST']);
    await screen.findByRole('table', { name: 'Registered admissions' });
    await openRegisterForm(user);

    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/select a patient/i);
    expect(state.postCalls).toHaveLength(0);
  });

  it('shows the server error inline and keeps every entered value when a selected reference is stale (404)', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: ADMISSIONS_INITIAL,
      postResponse: jsonResponse({ message: 'Patient not found' }, 404),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAdmissions(['ADMIN']);
    await screen.findByRole('table', { name: 'Registered admissions' });
    await openRegisterForm(user);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    fireEvent.change(screen.getByLabelText('Admitted at'), {
      target: { value: '2031-04-02T14:15' },
    });
    await user.type(screen.getByLabelText('Reason'), 'Synthetic stale reference');

    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/404/);

    // No data loss: every selection survives the failed submit.
    expect(screen.getByLabelText('Patient')).toHaveValue(PATIENT_A.id);
    expect(screen.getByLabelText('Admitted at')).toHaveValue('2031-04-02T14:15');
    expect(screen.getByLabelText('Reason')).toHaveValue('Synthetic stale reference');

    // A failed create never refreshes the list.
    const admissionGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/admissions' && (options?.method ?? 'GET') === 'GET');
    expect(admissionGetCalls).toHaveLength(1);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows a clear 403 state when the server refuses the registration and keeps the entered values', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: ADMISSIONS_INITIAL,
      postResponse: jsonResponse({ message: 'access denied' }, 403),
    };
    stubBackend(fetchMock, state);
    renderAdmissions(['DOCTOR']);
    await screen.findByRole('table', { name: 'Registered admissions' });
    await openRegisterForm(user);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_B.id);
    fireEvent.change(screen.getByLabelText('Admitted at'), {
      target: { value: '2031-05-10T08:00' },
    });
    await user.type(screen.getByLabelText('Reason'), 'Synthetic refused admission');

    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');
    expect(screen.getByLabelText('Patient')).toHaveValue(PATIENT_B.id);
  });

  it('returns to the shell on session expiry instead of raising a local error', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: ADMISSIONS_INITIAL,
      postResponse: jsonResponse({ message: 'authentication required' }, 401),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAdmissions(['ADMIN']);
    await screen.findByRole('table', { name: 'Registered admissions' });
    await openRegisterForm(user);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    fireEvent.change(screen.getByLabelText('Admitted at'), {
      target: { value: '2031-06-01T11:00' },
    });
    await user.type(screen.getByLabelText('Reason'), 'Synthetic expired session');

    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('discharges an admitted admission only after deliberate confirmation and never offers it twice', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], admissionsList: ADMISSIONS_INITIAL };
    stubBackend(fetchMock, state);
    renderAdmissions(['NURSE']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const admittedRow = within(table).getByRole('row', { name: /Amal Hassan/ });
    const dischargedRow = within(table).getByRole('row', { name: /Omar Diab/ });
    // Only the open admission offers the discharge action.
    expect(within(dischargedRow).queryByRole('button', { name: 'Discharge' })).not.toBeInTheDocument();

    await user.click(within(admittedRow).getByRole('button', { name: 'Discharge' }));

    // Deliberate confirmation step before anything is sent.
    const confirm = within(admittedRow).getByRole('button', { name: 'Confirm discharge' });
    expect(within(admittedRow).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(state.putCalls).toHaveLength(0);

    // Cancel keeps the admission untouched.
    await user.click(within(admittedRow).getByRole('button', { name: 'Cancel' }));
    expect(within(admittedRow).getByRole('button', { name: 'Discharge' })).toBeInTheDocument();
    expect(state.putCalls).toHaveLength(0);

    await user.click(within(admittedRow).getByRole('button', { name: 'Discharge' }));
    // The list refetch is issued during the confirm click's act flush, so
    // the refreshed payload is staged before confirming, not after.
    state.admissionsList = ADMISSIONS_AFTER_DISCHARGE;
    await user.click(within(admittedRow).getByRole('button', { name: 'Confirm discharge' }));

    await waitFor(() => expect(state.putCalls).toHaveLength(1));
    expect(state.putCalls[0].path).toBe(`/api/admissions/${ADMISSION_ADMITTED.id}/status`);
    expect(state.putCalls[0].body).toEqual({ status: 'DISCHARGED' });

    // The list refetches; the discharged record shows its state and no
    // further discharge action.
    const refreshedTable = await screen.findByRole('table', { name: 'Registered admissions' });
    const refreshedRow = await within(refreshedTable).findByRole('row', { name: /Amal Hassan/ });
    await waitFor(() => expect(within(refreshedRow).getByText('DISCHARGED')).toBeInTheDocument());
    expect(within(refreshedRow).queryByRole('button', { name: 'Discharge' })).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/admission discharged/i);
    expect(within(refreshedTable).queryAllByRole('button', { name: 'Discharge' })).toHaveLength(0);
  });

  it('shows the conflict message when the server refuses the discharge (409) and keeps the row admitted', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: ADMISSIONS_INITIAL,
      putResponse: jsonResponse({ message: 'Admission cannot be discharged: it is already DISCHARGED' }, 409),
    };
    stubBackend(fetchMock, state);
    renderAdmissions(['RECEPTIONIST']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const admittedRow = within(table).getByRole('row', { name: /Amal Hassan/ });

    await user.click(within(admittedRow).getByRole('button', { name: 'Discharge' }));
    await user.click(within(admittedRow).getByRole('button', { name: 'Confirm discharge' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/409/);

    // The row stays admitted with its action available; no list refresh.
    expect(within(admittedRow).getByText('ADMITTED')).toBeInTheDocument();
    const admissionGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/admissions' && (options?.method ?? 'GET') === 'GET');
    expect(admissionGetCalls).toHaveLength(1);
  });

  it('offers register and discharge to every clinical-administrative role', async () => {
    const state = { postCalls: [], putCalls: [], admissionsList: ADMISSIONS_INITIAL };
    stubBackend(fetchMock, state);

    for (const role of ['ADMIN', 'DOCTOR', 'NURSE', 'RECEPTIONIST']) {
      const view = renderAdmissions([role]);
      const table = await screen.findByRole('table', { name: 'Registered admissions' });
      expect(screen.getByRole('button', { name: 'Register admission' })).toBeInTheDocument();
      expect(within(table).getByRole('button', { name: 'Discharge' })).toBeInTheDocument();
      view.unmount();
    }
  });

  it('registers an admission from the patient detail view with that patient preselected, preserving existing detail actions', async () => {
    const user = userEvent.setup();
    const onSessionExpired = vi.fn();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: [],
      postResponse: jsonResponse({ ...ADMISSION_ADMITTED, reason: 'Synthetic detail admission' }),
    };
    stubBackend(fetchMock, state);

    const { unmount } = render(
      <PatientDetailPage
        patient={PATIENT_A}
        session={sessionFor(['RECEPTIONIST'])}
        onEdit={vi.fn()}
        onClose={vi.fn()}
        onSessionExpired={onSessionExpired}
      />
    );

    // Existing detail behavior stays: the schedule action is still offered.
    expect(screen.getByRole('button', { name: 'Schedule appointment' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    const patientSelect = await screen.findByLabelText('Patient');
    await waitFor(() => expect(patientSelect).toHaveValue(PATIENT_A.id));
    const patientOptions = within(patientSelect).getAllByRole('option');
    expect(patientOptions).toHaveLength(2); // placeholder + the selected record only
    expect(patientOptions[1]).toHaveTextContent(/Amal Hassan/);

    fireEvent.change(screen.getByLabelText('Admitted at'), {
      target: { value: '2031-07-01T08:00' },
    });
    await user.type(screen.getByLabelText('Reason'), 'Synthetic detail admission');
    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    await waitFor(() => expect(state.postCalls).toHaveLength(1));
    expect(state.postCalls[0]).toEqual({
      patientId: PATIENT_A.id,
      admittedAt: '2031-07-01T08:00',
      reason: 'Synthetic detail admission',
    });
    // The form closes and the confirmation surfaces in the detail view.
    await screen.findByRole('status');
    expect(screen.getByRole('status')).toHaveTextContent(/admission registered/i);
    expect(screen.queryByLabelText('Reason')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register admission' })).toBeInTheDocument();
    unmount();

    // DOCTOR may register admissions too (server family rule mirrors the
    // four-role admissions family), but never schedules — appointment
    // create stays ADMIN/RECEPTIONIST (plan1.md Task 9 matrix).
    render(
      <PatientDetailPage
        patient={PATIENT_A}
        session={sessionFor(['DOCTOR'])}
        onEdit={vi.fn()}
        onClose={vi.fn()}
        onSessionExpired={vi.fn()}
      />
    );
    expect(screen.getByRole('button', { name: 'Register admission' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Schedule appointment' })).not.toBeInTheDocument();
  });

  it('cancels the detail-view admission form and returns to the unchanged detail view', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], admissionsList: [] };
    stubBackend(fetchMock, state);

    render(
      <PatientDetailPage
        patient={PATIENT_A}
        session={sessionFor(['NURSE'])}
        onEdit={vi.fn()}
        onClose={vi.fn()}
        onSessionExpired={vi.fn()}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Register admission' }));
    expect(await screen.findByLabelText('Reason')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByLabelText('Patient')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register admission' })).toBeInTheDocument();
    expect(state.postCalls).toHaveLength(0);
  });
});
