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

// docs/plan3.md Task 7 bed fixtures: the branch bed DTO contract is
// id/branchId/ward/room/bedNumber/occupancyStatus, and only AVAILABLE beds
// may ever be offered as a create/assign/transfer target.
const BED_AVAILABLE_A = {
  id: '88888888-8888-4888-8888-888888888821',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward A',
  room: '101',
  bedNumber: 'A-01',
  occupancyStatus: 'AVAILABLE',
};

const BED_AVAILABLE_B = {
  id: '88888888-8888-4888-8888-888888888822',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward A',
  room: '102',
  bedNumber: 'A-02',
  occupancyStatus: 'AVAILABLE',
};

const BED_MAINTENANCE = {
  id: '88888888-8888-4888-8888-888888888823',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward B',
  room: '202',
  bedNumber: 'B-02',
  occupancyStatus: 'MAINTENANCE',
};

const BED_OCCUPIED = {
  id: '88888888-8888-4888-8888-888888888824',
  branchId: '44444444-4444-4444-8444-444444444444',
  ward: 'Ward C',
  room: '303',
  bedNumber: 'C-03',
  occupancyStatus: 'OCCUPIED',
};

const BEDS_TASK7 = [BED_AVAILABLE_A, BED_AVAILABLE_B, BED_MAINTENANCE, BED_OCCUPIED];

// An active admission that already holds a bed: currentBed is the
// allowlisted { bedId, ward, room, bedNumber } summary only.
const ADMISSION_WITH_BED = {
  id: '99999999-9999-4999-8999-999999999905',
  patientId: PATIENT_A.id,
  admittedAt: '2031-05-01T08:00',
  dischargedAt: null,
  reason: 'Synthetic held bed',
  status: 'ADMITTED',
  currentBed: {
    bedId: BED_OCCUPIED.id,
    ward: BED_OCCUPIED.ward,
    room: BED_OCCUPIED.room,
    bedNumber: BED_OCCUPIED.bedNumber,
  },
};

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
    state.bedPutCalls = state.bedPutCalls ?? [];
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
    if (path === '/api/beds') {
      return Promise.resolve(jsonResponse(state.bedList ?? BEDS_TASK7, state.bedListStatus ?? 200));
    }
    if (/^\/api\/admissions\/[^/]+\/bed$/.test(path)) {
      state.bedPutCalls.push({ path, body: JSON.parse(options.body) });
      if (state.bedPutResponse) return Promise.resolve(state.bedPutResponse);
      return Promise.resolve(jsonResponse(ADMISSION_WITH_BED));
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
      if (path === '/api/beds') return Promise.resolve(jsonResponse(BEDS_TASK7));
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
    // The release also refetches the bed inventory: the freed bed is
    // AVAILABLE again only through server truth (mount + refresh).
    const bedGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/beds' && (options?.method ?? 'GET') === 'GET');
    expect(bedGetCalls).toHaveLength(2);
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
      // Task 7: the bed command (assignment here — the row holds no bed) is
      // offered to exactly the same clinical-administrative roles.
      expect(within(table).getByRole('button', { name: 'Assign bed' })).toBeInTheDocument();
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

  // --- docs/plan3.md Task 7: admission/bed integration ---------------------

  it('renders the held bed for an active admission, an explicit no-bed state, and never a raw bed id', async () => {
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: [ADMISSION_WITH_BED, ADMISSION_ADMITTED, ADMISSION_DISCHARGED],
    };
    stubBackend(fetchMock, state);
    renderAdmissions(['NURSE']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const heldRow = within(table).getByRole('row', { name: /Synthetic held bed/ });
    expect(within(heldRow).getByText('Bed C-03 — Ward C · 303')).toBeInTheDocument();

    const noBedRow = within(table).getByRole('row', { name: /Synthetic observation stay/ });
    expect(within(noBedRow).getByText('No bed assigned')).toBeInTheDocument();

    const dischargedRow = within(table).getByRole('row', { name: /Synthetic completed stay/ });
    expect(within(dischargedRow).queryByText('No bed assigned')).not.toBeInTheDocument();
    // Closed admissions keep an em dash in the current-bed column, and no
    // raw bed reference value ever reaches the table.
    expect(within(dischargedRow).getAllByText('—').length).toBeGreaterThanOrEqual(1);
    expect(table).not.toHaveTextContent(BED_OCCUPIED.id);
  });

  it('creates an admission with the exact selected bed and refetches admissions and beds', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], admissionsList: ADMISSIONS_INITIAL };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAdmissions(['NURSE']);
    await screen.findByRole('table', { name: 'Registered admissions' });
    await openRegisterForm(user);

    // The optional selector offers ONLY the beds the branch reports
    // AVAILABLE: placeholder + two available, never MAINTENANCE or OCCUPIED.
    const bedSelect = screen.getByLabelText('Bed (optional)');
    await waitFor(() => expect(within(bedSelect).getAllByRole('option')).toHaveLength(3));
    expect(bedSelect).not.toHaveTextContent('B-02');
    expect(bedSelect).not.toHaveTextContent('C-03');
    await user.selectOptions(bedSelect, BED_AVAILABLE_A.id);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    fireEvent.change(screen.getByLabelText('Admitted at'), {
      target: { value: '2031-04-01T09:30' },
    });
    await user.type(screen.getByLabelText('Reason'), 'Synthetic bedded admission');

    // The refreshed payloads are staged before the submit click's act flush.
    state.admissionsList = [{
      ...ADMISSION_ADMITTED,
      id: '99999999-9999-4999-8999-999999999906',
      reason: 'Synthetic bedded admission',
      currentBed: {
        bedId: BED_AVAILABLE_A.id,
        ward: BED_AVAILABLE_A.ward,
        room: BED_AVAILABLE_A.room,
        bedNumber: BED_AVAILABLE_A.bedNumber,
      },
    }, ...ADMISSIONS_INITIAL];
    await user.click(screen.getByRole('button', { name: 'Register admission' }));

    await waitFor(() => expect(state.postCalls).toHaveLength(1));
    // The selected bed travels as exactly one extra contract field.
    expect(Object.keys(state.postCalls[0]).sort())
      .toEqual(['admittedAt', 'bedId', 'patientId', 'reason']);
    expect(state.postCalls[0].bedId).toBe(BED_AVAILABLE_A.id);

    // Both views reflect server truth: a fresh admissions GET and a fresh
    // beds GET follow the create (page mount + form mount + refresh).
    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    await waitFor(() => {
      expect(within(table).getByText('Bed A-01 — Ward A · 101')).toBeInTheDocument();
    });
    const admissionGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/admissions' && (options?.method ?? 'GET') === 'GET');
    expect(admissionGetCalls).toHaveLength(2);
    const bedGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/beds' && (options?.method ?? 'GET') === 'GET');
    expect(bedGetCalls).toHaveLength(3);
    expect(screen.getByRole('status')).toHaveTextContent(/admission registered/i);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('assigns a bed to an active admission only after explicit confirmation, offering only AVAILABLE beds, and refetches both views', async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], putCalls: [], admissionsList: [ADMISSION_ADMITTED] };
    stubBackend(fetchMock, state);
    renderAdmissions(['RECEPTIONIST']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const row = within(table).getByRole('row', { name: /Synthetic observation stay/ });
    expect(within(row).getByText('No bed assigned')).toBeInTheDocument();

    await user.click(within(row).getByRole('button', { name: 'Assign bed' }));

    // Labeled select, deliberate confirmation, and nothing sent yet.
    const targetSelect = within(row).getByLabelText('Available bed');
    expect(within(targetSelect).getAllByRole('option')).toHaveLength(3); // placeholder + two available
    expect(targetSelect).not.toHaveTextContent('B-02');
    expect(targetSelect).not.toHaveTextContent('C-03');
    expect(state.bedPutCalls).toHaveLength(0);

    // Confirming without a selection shows an inline error and never sends.
    await user.click(within(row).getByRole('button', { name: 'Confirm assignment' }));
    expect(within(row).getByRole('alert')).toHaveTextContent(/select an available bed/i);
    expect(state.bedPutCalls).toHaveLength(0);

    state.admissionsList = [{
      ...ADMISSION_ADMITTED,
      currentBed: {
        bedId: BED_AVAILABLE_A.id,
        ward: BED_AVAILABLE_A.ward,
        room: BED_AVAILABLE_A.room,
        bedNumber: BED_AVAILABLE_A.bedNumber,
      },
    }];
    state.bedList = [
      { ...BED_AVAILABLE_A, occupancyStatus: 'OCCUPIED' },
      BED_AVAILABLE_B,
      BED_MAINTENANCE,
      BED_OCCUPIED,
    ];
    await user.selectOptions(targetSelect, BED_AVAILABLE_A.id);
    await user.click(within(row).getByRole('button', { name: 'Confirm assignment' }));

    await waitFor(() => expect(state.bedPutCalls).toHaveLength(1));
    expect(state.bedPutCalls[0].path).toBe(`/api/admissions/${ADMISSION_ADMITTED.id}/bed`);
    expect(state.bedPutCalls[0].body).toEqual({ bedId: BED_AVAILABLE_A.id });
    expect(screen.getByRole('status')).toHaveTextContent('Bed assigned.');

    // The row shows the held bed from the fresh server response and now
    // offers transfer instead of assignment.
    const refreshedRow = within(table).getByRole('row', { name: /Synthetic observation stay/ });
    await waitFor(() => {
      expect(within(refreshedRow).getByText('Bed A-01 — Ward A · 101')).toBeInTheDocument();
    });
    expect(within(refreshedRow).getByRole('button', { name: 'Transfer bed' })).toBeInTheDocument();
    expect(within(refreshedRow).queryByRole('button', { name: 'Assign bed' })).not.toBeInTheDocument();

    // Both authoritative lists refetched: admissions mount+refresh and beds
    // mount+refresh (no form was opened on this screen).
    const admissionGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/admissions' && (options?.method ?? 'GET') === 'GET');
    expect(admissionGetCalls).toHaveLength(2);
    const bedGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/beds' && (options?.method ?? 'GET') === 'GET');
    expect(bedGetCalls).toHaveLength(2);
  });

  it('transfers a held bed without ever offering the currently held bed', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: [ADMISSION_WITH_BED],
      bedList: [BED_AVAILABLE_A, BED_MAINTENANCE, BED_OCCUPIED],
    };
    stubBackend(fetchMock, state);
    renderAdmissions(['DOCTOR']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const row = within(table).getByRole('row', { name: /Synthetic held bed/ });
    expect(within(row).getByText('Bed C-03 — Ward C · 303')).toBeInTheDocument();

    await user.click(within(row).getByRole('button', { name: 'Transfer bed' }));

    // The held bed is never among the target choices.
    const targetSelect = within(row).getByLabelText('Transfer to available bed');
    const optionValues = within(targetSelect).getAllByRole('option').map((option) => option.value);
    expect(optionValues).toEqual(['', BED_AVAILABLE_A.id]);
    expect(optionValues).not.toContain(BED_OCCUPIED.id);

    // Cancel keeps the held bed untouched and sends nothing.
    await user.click(within(row).getByRole('button', { name: 'Cancel' }));
    expect(state.bedPutCalls).toHaveLength(0);
    expect(within(row).getByRole('button', { name: 'Transfer bed' })).toBeInTheDocument();

    await user.click(within(row).getByRole('button', { name: 'Transfer bed' }));
    state.admissionsList = [{
      ...ADMISSION_WITH_BED,
      currentBed: {
        bedId: BED_AVAILABLE_A.id,
        ward: BED_AVAILABLE_A.ward,
        room: BED_AVAILABLE_A.room,
        bedNumber: BED_AVAILABLE_A.bedNumber,
      },
    }];
    state.bedList = [
      { ...BED_AVAILABLE_A, occupancyStatus: 'OCCUPIED' },
      BED_MAINTENANCE,
      { ...BED_OCCUPIED, occupancyStatus: 'AVAILABLE' },
    ];
    await user.selectOptions(within(row).getByLabelText('Transfer to available bed'), BED_AVAILABLE_A.id);
    await user.click(within(row).getByRole('button', { name: 'Confirm transfer' }));

    await waitFor(() => expect(state.bedPutCalls).toHaveLength(1));
    expect(state.bedPutCalls[0].path).toBe(`/api/admissions/${ADMISSION_WITH_BED.id}/bed`);
    expect(state.bedPutCalls[0].body).toEqual({ bedId: BED_AVAILABLE_A.id });
    expect(screen.getByRole('status')).toHaveTextContent('Bed transferred.');

    // The refreshed admission holds the new bed; the released bed is only
    // AVAILABLE again in the refetched inventory.
    const refreshedRow = within(table).getByRole('row', { name: /Synthetic held bed/ });
    await waitFor(() => {
      expect(within(refreshedRow).getByText('Bed A-01 — Ward A · 101')).toBeInTheDocument();
    });
    expect(within(refreshedRow).queryByText('Bed C-03 — Ward C · 303')).not.toBeInTheDocument();
    const bedGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/beds' && (options?.method ?? 'GET') === 'GET');
    expect(bedGetCalls).toHaveLength(2);
  });

  it('keeps the confirmation, the selection, and the row state when the server refuses the bed command (409)', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: [ADMISSION_ADMITTED],
      bedPutResponse: jsonResponse({ message: 'Bed is not AVAILABLE' }, 409),
    };
    stubBackend(fetchMock, state);
    renderAdmissions(['NURSE']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const row = within(table).getByRole('row', { name: /Synthetic observation stay/ });

    await user.click(within(row).getByRole('button', { name: 'Assign bed' }));
    await user.selectOptions(within(row).getByLabelText('Available bed'), BED_AVAILABLE_A.id);
    await user.click(within(row).getByRole('button', { name: 'Confirm assignment' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/409/);
    // The current admission/bed display is untouched: still ADMITTED with no
    // bed, the selection survives, and the command can be retried.
    expect(within(row).getByText('ADMITTED')).toBeInTheDocument();
    expect(within(row).getByText('No bed assigned')).toBeInTheDocument();
    expect(within(row).getByLabelText('Available bed')).toHaveValue(BED_AVAILABLE_A.id);
    expect(within(row).getByRole('button', { name: 'Confirm assignment' })).toBeEnabled();
    // A failed command never refetches either list.
    const admissionGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/admissions' && (options?.method ?? 'GET') === 'GET');
    expect(admissionGetCalls).toHaveLength(1);
    const bedGetCalls = fetchMock.mock.calls.filter(([path, options]) => path === '/api/beds' && (options?.method ?? 'GET') === 'GET');
    expect(bedGetCalls).toHaveLength(1);
  });

  it('delegates a 401 bed command to shell session expiry instead of raising a local error', async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      putCalls: [],
      admissionsList: [ADMISSION_ADMITTED],
      bedPutResponse: jsonResponse({ message: 'authentication required' }, 401),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAdmissions(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Registered admissions' });
    const row = within(table).getByRole('row', { name: /Synthetic observation stay/ });
    await user.click(within(row).getByRole('button', { name: 'Assign bed' }));
    await user.selectOptions(within(row).getByLabelText('Available bed'), BED_AVAILABLE_A.id);
    await user.click(within(row).getByRole('button', { name: 'Confirm assignment' }));

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
