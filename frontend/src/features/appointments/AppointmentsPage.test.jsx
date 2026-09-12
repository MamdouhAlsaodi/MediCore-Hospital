import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AppointmentsPage from './AppointmentsPage.jsx';
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

const CARDIOLOGIST = {
  id: '33333333-3333-4333-8333-333333333333',
  employeeCode: 'EMP-2001',
  fullName: 'Layla Fawzi',
  profession: 'Cardiologist',
  licenseNumber: 'LIC-2001',
  department: 'Cardiology',
};

const GP = {
  id: '44444444-4444-4444-8444-444444444444',
  employeeCode: 'EMP-2002',
  fullName: 'Karim Nabil',
  profession: 'General Practitioner',
  licenseNumber: 'LIC-2002',
  department: 'Outpatient Clinic',
};

const STAFF = [CARDIOLOGIST, GP];

const CARDIOLOGIST_LABEL = 'Layla Fawzi — Cardiologist (Cardiology)';

// Pre-Task-4 row shape: patient/professional references are raw stored
// strings, not verified UUID records. The list must render honest
// unresolved placeholders instead of raw reference values.
const LEGACY_APPOINTMENT = {
  id: '55555555-5555-4555-8555-555555555555',
  patientId: 'raw pre-Task-4 patient reference',
  professionalId: 'raw pre-Task-4 professional reference',
  scheduledAt: '2026-01-15T10:00',
  type: 'Follow-up',
  status: 'completed',
};

const KNOWN_APPOINTMENT = {
  id: '66666666-6666-4666-8666-666666666666',
  patientId: PATIENT_A.id,
  professionalId: CARDIOLOGIST.id,
  scheduledAt: '2026-03-01T09:30',
  type: 'Consultation',
  status: 'scheduled',
};

const APPOINTMENTS_INITIAL = [LEGACY_APPOINTMENT];
const APPOINTMENTS_AFTER_CREATE = [KNOWN_APPOINTMENT, {
  id: '77777777-7777-4777-8777-777777777777',
  patientId: PATIENT_A.id,
  professionalId: CARDIOLOGIST.id,
  scheduledAt: '2026-03-01T09:30',
  type: 'Consultation',
  status: 'scheduled',
}];

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function sessionFor(roles) {
  return { token: 'synthetic-token', username: 'testuser', roles };
}

function renderAppointments(roles = ['ADMIN']) {
  const onSessionExpired = vi.fn();
  const view = render(
    <AppointmentsPage session={sessionFor(roles)} onSessionExpired={onSessionExpired} />
  );
  return { onSessionExpired, ...view };
}

// Router over the three consumed endpoints. `state` is read live so tests
// can switch the POST outcome or the list payload between calls.
function stubBackend(fetchMock, state) {
  fetchMock.mockImplementation((path, options = {}) => {
    const method = options.method ?? 'GET';
    if (path === '/api/patients') {
      return Promise.resolve(jsonResponse(PATIENTS, state.patientsStatus ?? 200));
    }
    if (path === '/api/staff') {
      return Promise.resolve(jsonResponse(STAFF, state.staffStatus ?? 200));
    }
    if (path === '/api/appointments') {
      if (method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        if (state.postResponse) return Promise.resolve(state.postResponse);
        return Promise.resolve(jsonResponse(KNOWN_APPOINTMENT));
      }
      return Promise.resolve(jsonResponse(state.appointmentsList, state.appointmentsStatus ?? 200));
    }
    return Promise.resolve(jsonResponse({}, 404));
  });
}

async function openScheduleForm(user) {
  await user.click(screen.getByRole('button', { name: 'Schedule appointment' }));
  const patientSelect = await screen.findByLabelText('Patient');
  // The professional select is filled by a separate request; wait for it.
  await waitFor(() =>
    expect(screen.getByLabelText('Professional')).toHaveValue('')
  );
  await waitFor(() =>
    expect(within(screen.getByLabelText('Professional')).getAllByRole('option').length).toBeGreaterThan(1)
  );
  return patientSelect;
}

describe('AppointmentsPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('loads patients and professionals into labeled selects when the schedule form opens', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], appointmentsList: APPOINTMENTS_INITIAL };
    stubBackend(fetchMock, state);
    renderAppointments(['ADMIN']);

    // The screen first shows the honest list of existing appointments.
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    const staffCall = fetchMock.mock.calls.find(([path]) => path === '/api/staff');
    expect(staffCall[1].headers.Authorization).toBe('Bearer synthetic-token');

    await openScheduleForm(user);

    const patientSelect = screen.getByLabelText('Patient');
    const patientOptions = within(patientSelect).getAllByRole('option');
    expect(patientOptions).toHaveLength(3); // placeholder + two real records
    expect(patientOptions[1]).toHaveValue(PATIENT_A.id);
    expect(patientOptions[1]).toHaveTextContent(/Amal Hassan/);
    expect(patientOptions[2]).toHaveValue(PATIENT_B.id);

    const professionalSelect = screen.getByLabelText('Professional');
    const professionalOptions = within(professionalSelect).getAllByRole('option');
    expect(professionalOptions).toHaveLength(3); // placeholder + two real records
    expect(professionalOptions[1]).toHaveValue(CARDIOLOGIST.id);
    expect(professionalOptions[1]).toHaveTextContent(CARDIOLOGIST_LABEL);
    expect(professionalOptions[2]).toHaveValue(GP.id);
  });

  it('schedules with the selected domain records on the typed contract, keeps the submit disabled while pending, and refreshes the list', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    let resolvePost;
    const state = {
      postCalls: [],
      appointmentsList: APPOINTMENTS_INITIAL,
    };
    stubBackend(fetchMock, state);
    fetchMock.mockImplementation((path, options = {}) => {
      const method = options.method ?? 'GET';
      if (path === '/api/appointments' && method === 'POST') {
        state.postCalls.push(JSON.parse(options.body));
        return new Promise((resolve) => { resolvePost = resolve; });
      }
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      if (path === '/api/staff') return Promise.resolve(jsonResponse(STAFF));
      if (path === '/api/appointments') return Promise.resolve(jsonResponse(state.appointmentsList));
      return Promise.resolve(jsonResponse({}, 404));
    });

    const { onSessionExpired } = renderAppointments(['ADMIN']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    await openScheduleForm(user);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    await user.selectOptions(screen.getByLabelText('Professional'), CARDIOLOGIST.id);
    fireEvent.change(screen.getByLabelText('Date and time'), {
      target: { value: '2026-03-01T09:30' },
    });
    await user.type(screen.getByLabelText('Type'), 'Consultation');
    // 'scheduled' is the initial value of the lowercase status contract.
    expect(screen.getByLabelText('Status')).toHaveValue('scheduled');

    await user.click(screen.getByRole('button', { name: 'Schedule appointment' }));

    // While the create request is in flight the submit is disabled.
    const pendingButton = await screen.findByRole('button', { name: 'Scheduling…' });
    expect(pendingButton).toBeDisabled();

    state.appointmentsList = APPOINTMENTS_AFTER_CREATE;
    resolvePost(jsonResponse(KNOWN_APPOINTMENT));

    // List refresh: the new appointment appears through a fresh GET.
    const table = await screen.findByRole('table', { name: 'Scheduled appointments' });
    await waitFor(() => {
      expect(within(table).getAllByRole('row', { name: /Consultation/ })).toHaveLength(2);
    });
    expect(screen.getByRole('status')).toHaveTextContent(/scheduled/i);

    expect(state.postCalls).toHaveLength(1);
    // IDs are the selected domain records, not typed opaque strings, and the
    // body mirrors CreateAppointmentRequest exactly.
    expect(state.postCalls[0]).toEqual({
      patientId: PATIENT_A.id,
      professionalId: CARDIOLOGIST.id,
      scheduledAt: '2026-03-01T09:30',
      type: 'Consultation',
      status: 'scheduled',
    });
    const postCall = fetchMock.mock.calls.find(([path, options]) => path === '/api/appointments' && options?.method === 'POST');
    expect(postCall[1].headers.Authorization).toBe('Bearer synthetic-token');
    expect(postCall[1].headers['Content-Type']).toBe('application/json');
    const appointmentGets = fetchMock.mock.calls.filter(([path, options]) => path === '/api/appointments' && (options?.method ?? 'GET') === 'GET');
    expect(appointmentGets).toHaveLength(2);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows the server error inline and keeps every entered value when a selected reference is stale (404)', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      appointmentsList: APPOINTMENTS_INITIAL,
      postResponse: jsonResponse({ message: 'Professional not found' }, 404),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAppointments(['ADMIN']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    await openScheduleForm(user);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    await user.selectOptions(screen.getByLabelText('Professional'), GP.id);
    fireEvent.change(screen.getByLabelText('Date and time'), {
      target: { value: '2026-04-02T14:15' },
    });
    await user.type(screen.getByLabelText('Type'), 'Follow-up');

    await user.click(screen.getByRole('button', { name: 'Schedule appointment' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/404/);

    // No data loss: every selection survives the failed submit.
    expect(screen.getByLabelText('Patient')).toHaveValue(PATIENT_A.id);
    expect(screen.getByLabelText('Professional')).toHaveValue(GP.id);
    expect(screen.getByLabelText('Date and time')).toHaveValue('2026-04-02T14:15');
    expect(screen.getByLabelText('Type')).toHaveValue('Follow-up');

    // A failed create never refreshes the list.
    const appointmentGets = fetchMock.mock.calls.filter(([path, options]) => path === '/api/appointments' && (options?.method ?? 'GET') === 'GET');
    expect(appointmentGets).toHaveLength(1);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('shows a clear 403 state when the server refuses the schedule and keeps the entered values', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      appointmentsList: APPOINTMENTS_INITIAL,
      postResponse: jsonResponse({ message: 'access denied' }, 403),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAppointments(['RECEPTIONIST']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    await openScheduleForm(user);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_B.id);
    await user.selectOptions(screen.getByLabelText('Professional'), CARDIOLOGIST.id);
    fireEvent.change(screen.getByLabelText('Date and time'), {
      target: { value: '2026-05-10T08:00' },
    });
    await user.type(screen.getByLabelText('Type'), 'Consultation');

    await user.click(screen.getByRole('button', { name: 'Schedule appointment' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');
    expect(screen.getByLabelText('Patient')).toHaveValue(PATIENT_B.id);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('returns to the shell on session expiry instead of raising a local error', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      appointmentsList: APPOINTMENTS_INITIAL,
      postResponse: jsonResponse({ message: 'authentication required' }, 401),
    };
    stubBackend(fetchMock, state);
    const { onSessionExpired } = renderAppointments(['ADMIN']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    await openScheduleForm(user);

    await user.selectOptions(screen.getByLabelText('Patient'), PATIENT_A.id);
    await user.selectOptions(screen.getByLabelText('Professional'), CARDIOLOGIST.id);
    fireEvent.change(screen.getByLabelText('Date and time'), {
      target: { value: '2026-06-01T11:00' },
    });
    await user.type(screen.getByLabelText('Type'), 'Consultation');

    await user.click(screen.getByRole('button', { name: 'Schedule appointment' }));

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('offers the schedule action only to roles permitted to schedule while the list stays readable', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    const state = { postCalls: [], appointmentsList: APPOINTMENTS_INITIAL };
    stubBackend(fetchMock, state);

    const doctor = renderAppointments(['DOCTOR']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    expect(screen.queryByRole('button', { name: 'Schedule appointment' })).not.toBeInTheDocument();
    doctor.unmount();

    const nurse = renderAppointments(['NURSE']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    expect(screen.queryByRole('button', { name: 'Schedule appointment' })).not.toBeInTheDocument();
    nurse.unmount();

    renderAppointments(['RECEPTIONIST']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });
    expect(screen.getByRole('button', { name: 'Schedule appointment' })).toBeInTheDocument();
  });

  it('renders the list with data-minimized columns and honest placeholders for unresolved references', { timeout: 20000 }, async () => {
    const state = {
      postCalls: [],
      appointmentsList: [LEGACY_APPOINTMENT, KNOWN_APPOINTMENT],
    };
    stubBackend(fetchMock, state);
    renderAppointments(['ADMIN']);

    const table = await screen.findByRole('table', { name: 'Scheduled appointments' });
    const resolvedRow = within(table).getByRole('row', { name: /Amal Hassan/ });
    expect(within(resolvedRow).getByText('Layla Fawzi — Cardiologist (Cardiology)')).toBeInTheDocument();
    expect(within(resolvedRow).getByText('2026-03-01T09:30')).toBeInTheDocument();
    expect(within(resolvedRow).getByText('Consultation')).toBeInTheDocument();
    expect(within(resolvedRow).getByText('scheduled')).toBeInTheDocument();

    const legacyRow = within(table).getByRole('row', { name: /Follow-up/ });
    expect(within(legacyRow).getAllByText('Unknown record')).toHaveLength(2);
    // Raw stored references are never rendered.
    expect(within(table).queryByText(/raw pre-Task-4/)).not.toBeInTheDocument();
  });

  it('shows an honest error when the appointments list fails to load', { timeout: 20000 }, async () => {
    const state = {
      postCalls: [],
      appointmentsList: [],
      appointmentsStatus: 500,
    };
    stubBackend(fetchMock, state);
    renderAppointments(['ADMIN']);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/500/);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('keeps the schedule form unavailable with a clear reason when the professional directory is refused (403)', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    const state = {
      postCalls: [],
      appointmentsList: APPOINTMENTS_INITIAL,
      staffStatus: 403,
    };
    stubBackend(fetchMock, state);
    renderAppointments(['RECEPTIONIST']);
    await screen.findByRole('table', { name: 'Scheduled appointments' });

    await user.click(screen.getByRole('button', { name: 'Schedule appointment' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('You do not have permission to view this data.');

    // Patients loaded normally; only the directory was refused.
    expect(within(screen.getByLabelText('Patient')).getAllByRole('option')).toHaveLength(3);

    const professionalSelect = screen.getByLabelText('Professional');
    expect(professionalSelect).toBeDisabled();
    expect(within(professionalSelect).getByRole('option')).toHaveTextContent(/not available/i);

    expect(screen.getByRole('button', { name: 'Schedule appointment' })).toBeDisabled();
  });

  it('schedules from the patient detail view with that patient preselected, offered only to permitted roles', { timeout: 20000 }, async () => {
    const user = userEvent.setup();
    const onSessionExpired = vi.fn();
    stubBackend(fetchMock, { postCalls: [], appointmentsList: [] });

    const { unmount } = render(
      <PatientDetailPage
        patient={PATIENT_A}
        session={sessionFor(['RECEPTIONIST'])}
        onEdit={vi.fn()}
        onClose={vi.fn()}
        onSessionExpired={onSessionExpired}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Schedule appointment' }));

    const patientSelect = await screen.findByLabelText('Patient');
    await waitFor(() => expect(patientSelect).toHaveValue(PATIENT_A.id));
    const patientOptions = within(patientSelect).getAllByRole('option');
    expect(patientOptions).toHaveLength(2); // placeholder + the selected record only
    expect(patientOptions[1]).toHaveTextContent(/Amal Hassan/);

    const professionalSelect = screen.getByLabelText('Professional');
    await waitFor(() =>
      expect(within(professionalSelect).getAllByRole('option')).toHaveLength(3)
    );

    // Cancellation returns to the unchanged detail view.
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Patient')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Schedule appointment' })).toBeInTheDocument();
    unmount();

    // DOCTOR inspects records but never sees the schedule action.
    render(
      <PatientDetailPage
        patient={PATIENT_A}
        session={sessionFor(['DOCTOR'])}
        onEdit={vi.fn()}
        onClose={vi.fn()}
        onSessionExpired={vi.fn()}
      />
    );
    expect(screen.queryByRole('button', { name: 'Schedule appointment' })).not.toBeInTheDocument();
  });

  it('refetches the branch-scoped appointment list under the new context-bound token after a successful context switch', async () => {
    const onSessionExpired = vi.fn();
    const assignmentA = {
      id: '11111111-1111-4111-8111-111111111111',
      role: 'RECEPTIONIST',
      scope: 'BRANCH',
      organizationId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      organizationLabel: 'Main Hospital Group',
      branchId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      branchLabel: 'East Clinic',
      departmentId: null,
      departmentLabel: null,
      enabled: true,
    };
    const assignmentB = { ...assignmentA, id: '22222222-2222-4222-8222-222222222222', branchId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', branchLabel: 'West Clinic' };
    const sessionA = {
      token: 'branch-a-token',
      username: 'testuser',
      roles: ['RECEPTIONIST'],
      assignments: [assignmentA, assignmentB],
      actingContext: {
        username: 'testuser',
        assignmentId: assignmentA.id,
        role: 'RECEPTIONIST',
        scope: 'BRANCH',
        organizationId: assignmentA.organizationId,
        branchId: assignmentA.branchId,
        departmentId: null,
      },
    };
    const sessionB = {
      ...sessionA,
      token: 'branch-b-token',
      actingContext: { ...sessionA.actingContext, assignmentId: assignmentB.id, branchId: assignmentB.branchId },
    };
    const state = { appointmentsList: APPOINTMENTS_INITIAL };
    stubBackend(fetchMock, state);

    const view = render(<AppointmentsPage session={sessionA} onSessionExpired={onSessionExpired} />);
    const tableA = await screen.findByRole('table', { name: 'Scheduled appointments' });
    expect(within(tableA).getAllByRole('row')).toHaveLength(2); // header + legacy row

    // Point the stubbed server at the other branch's data and swap in the
    // complete switched session: the screen stays the single owner of its
    // list and reloads it under the new context-bound token.
    state.appointmentsList = APPOINTMENTS_AFTER_CREATE;
    view.rerender(<AppointmentsPage session={sessionB} onSessionExpired={onSessionExpired} />);

    const tableB = await screen.findByRole('table', { name: 'Scheduled appointments' });
    expect(within(tableB).getAllByRole('row')).toHaveLength(3); // header + two branch-B rows
    const calls = fetchMock.mock.calls.filter(([path, options = {}]) =>
      path === '/api/appointments' && (options.method ?? 'GET') === 'GET');
    expect(calls).toHaveLength(2);
    expect(calls[0][1].headers.Authorization).toBe('Bearer branch-a-token');
    expect(calls[1][1].headers.Authorization).toBe('Bearer branch-b-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
});
