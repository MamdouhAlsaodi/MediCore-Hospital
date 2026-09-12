import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PatientsPage from './PatientsPage.jsx';

const PATIENTS = [
  {
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
  },
  {
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
  },
];

const UNSAFE_NAME = '<img src=x onerror="window.__leak=1">Rana <script>alert(1)</script>';

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderPatients(sessionOverrides = {}) {
  const onSessionExpired = vi.fn();
  const view = render(
    <PatientsPage
      session={{
        token: 'synthetic-token',
        username: 'testuser',
        roles: ['DOCTOR'],
        ...sessionOverrides,
      }}
      onSessionExpired={onSessionExpired}
    />
  );
  return { onSessionExpired, ...view };
}

async function waitForPatientList() {
  return screen.findByRole('list', { name: 'Patient results' });
}

describe('PatientsPage', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('shows an accessible loading status and calls the patients endpoint with the session token on initial load', async () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    renderPatients();

    expect(screen.getByRole('status')).toHaveTextContent(/loading/i);
    expect(screen.queryByRole('list', { name: 'Patient results' })).not.toBeInTheDocument();

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/patients');
    expect(options.method).toBe('GET');
    expect(options.headers.Authorization).toBe('Bearer synthetic-token');
  });

  it('renders the returned patients after the initial load completes', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(PATIENTS)));
    renderPatients();

    const list = await waitForPatientList();
    const rows = within(list).getAllByRole('button');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Amal Hassan');
    expect(rows[0]).toHaveTextContent('MRN-1001');
    expect(rows[0]).toHaveTextContent(/\bActive\b/);
    expect(rows[1]).toHaveTextContent('Omar Diab');
    expect(rows[1]).toHaveTextContent('Inactive');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/patients');
  });

  it('shows actionable search guidance when the result set is empty', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse([])));
    renderPatients();

    const empty = await screen.findByText(/no patients match|registered yet/i);
    expect(empty).toHaveTextContent(/search/i);
    expect(screen.queryByRole('list', { name: 'Patient results' })).not.toBeInTheDocument();
  });

  it('shows a visible network error when the server is unreachable', async () => {
    fetchMock.mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')));
    renderPatients();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/network error/i);
    expect(screen.queryByRole('list', { name: 'Patient results' })).not.toBeInTheDocument();
  });

  it('handles 401 by invoking the session-expiry callback instead of showing a local error', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({ error: 'expired' }, 401)));
    const { onSessionExpired } = renderPatients();

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a visible permission denial on 403', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({ error: 'forbidden' }, 403)));
    renderPatients();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/permission/i);
  });

  it('submits the search form to the patients endpoint with an encoded query', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((path) => {
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      return Promise.resolve(jsonResponse([]));
    });
    renderPatients();
    await waitForPatientList();

    await user.type(screen.getByRole('searchbox', { name: 'Search patients' }), 'Jo hn &?=');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls[1][0]).toBe('/api/patients?q=Jo%20hn%20%26%3F%3D');
    const emptyHint = await screen.findByText(/no patients match/i);
    expect(emptyHint).toHaveTextContent('Jo hn &?=');
  });

  it('keeps the typed query visible when the search fails with a server error', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((path) => {
      if (path === '/api/patients') return Promise.resolve(jsonResponse(PATIENTS));
      return Promise.resolve(jsonResponse({ error: 'boom' }, 500));
    });
    renderPatients();
    await waitForPatientList();

    const input = screen.getByRole('searchbox', { name: 'Search patients' });
    await user.type(input, 'Zed');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/failed \(500\)/i);
    expect(input).toHaveValue('Zed');
  });

  it('renders returned values only through React text nodes', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(jsonResponse([
        {
          id: '33333333-3333-4333-8333-333333333333',
          medicalRecordNumber: 'MRN-1003',
          fullName: UNSAFE_NAME,
          dateOfBirth: '',
          sex: '',
          phone: '',
          email: '',
          nationalId: '',
          address: '',
          active: true,
        },
      ]))
    );
    const { container } = renderPatients();

    await waitForPatientList();
    expect(screen.getByText(UNSAFE_NAME)).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
  });

  it('selects a patient via native button keyboard activation and opens a bounded read-only detail view', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(PATIENTS)));
    renderPatients();
    const list = await waitForPatientList();
    const rows = within(list).getAllByRole('button');

    rows[0].focus();
    await user.keyboard('{Enter}');

    const detail = screen.getByRole('region', { name: 'Selected patient' });
    expect(detail).toHaveTextContent('Amal Hassan');
    expect(detail).toHaveTextContent('MRN-1001');
    expect(detail).toHaveTextContent('1990-05-14');
    expect(within(detail).queryByText('National ID')).not.toBeInTheDocument();
    expect(detail).not.toHaveTextContent(PATIENTS[0].nationalId);
    expect(within(detail).getByText('Active')).toBeInTheDocument();
    expect(detail).toHaveTextContent(/read-only/i);

    await user.click(within(detail).getByRole('button', { name: 'View record form' }));
    const form = screen.getByRole('region', { name: 'Patient form' });
    expect(within(form).getByLabelText('Medical record number')).toBeDisabled();
    expect(within(form).getByLabelText('Full name')).toBeDisabled();
    expect(within(form).queryByRole('button', { name: /save/i })).not.toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Back to detail' })).toBeInTheDocument();

    await user.click(within(form).getByRole('button', { name: 'Back to detail' }));
    expect(screen.getByRole('region', { name: 'Selected patient' })).toHaveTextContent('Amal Hassan');

    const reopenedDetail = screen.getByRole('region', { name: 'Selected patient' });
    await user.click(within(reopenedDetail).getByRole('button', { name: 'Back to patient list' }));
    expect(screen.queryByRole('region', { name: 'Selected patient' })).not.toBeInTheDocument();

    const refreshed = await screen.findByRole('list', { name: 'Patient results' });
    await user.click(within(refreshed).getAllByRole('button')[1]);
    const secondDetail = screen.getByRole('region', { name: 'Selected patient' });
    expect(secondDetail).toHaveTextContent('Omar Diab');
    expect(within(secondDetail).getByText('Inactive')).toBeInTheDocument();
    expect(secondDetail).toHaveTextContent('—');
  });

  it('clears the selected-patient summary as soon as a new search begins loading', async () => {
    const user = userEvent.setup();
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(PATIENTS)))
      .mockImplementationOnce(() => new Promise(() => {}));
    renderPatients();
    const list = await waitForPatientList();

    await user.click(within(list).getAllByRole('button')[0]);
    expect(screen.getByRole('region', { name: 'Selected patient' })).toBeInTheDocument();

    await user.type(screen.getByRole('searchbox', { name: 'Search patients' }), 'Amal');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(screen.queryByRole('region', { name: 'Selected patient' })).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Patient results' })).not.toBeInTheDocument();
  });

  it('offers the New patient action only to roles permitted to register patients', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(PATIENTS)));

    const receptionist = renderPatients({ roles: ['RECEPTIONIST'] });
    await waitForPatientList();
    expect(screen.getByRole('button', { name: 'New patient' })).toBeInTheDocument();
    receptionist.unmount();

    renderPatients({ roles: ['NURSE'] });
    await waitForPatientList();
    expect(screen.queryByRole('button', { name: 'New patient' })).not.toBeInTheDocument();
  });

  it('registers a new patient and returns to the detail view with a visible confirmation', async () => {
    const user = userEvent.setup();
    const CREATED = {
      id: '99999999-9999-4999-8999-999999999999',
      medicalRecordNumber: 'MRN-2001',
      fullName: 'New Synthetic Patient',
      dateOfBirth: '',
      sex: '',
      phone: '',
      email: '',
      nationalId: '',
      address: '',
      active: true,
    };
    fetchMock.mockImplementation((path, options = {}) => {
      if (path === '/api/patients' && options.method === 'POST') {
        return Promise.resolve(jsonResponse(CREATED, 201));
      }
      return Promise.resolve(jsonResponse(PATIENTS));
    });
    renderPatients({ roles: ['RECEPTIONIST'] });
    await waitForPatientList();

    await user.click(screen.getByRole('button', { name: 'New patient' }));
    const form = screen.getByRole('region', { name: 'Patient form' });
    await user.type(within(form).getByLabelText('Medical record number'), 'MRN-2001');
    await user.type(within(form).getByLabelText('Full name'), 'New Synthetic Patient');
    await user.click(within(form).getByRole('button', { name: 'Save patient' }));

    const post = fetchMock.mock.calls.find(([, options]) => options.method === 'POST');
    expect(post[0]).toBe('/api/patients');
    expect(JSON.parse(post[1].body).medicalRecordNumber).toBe('MRN-2001');

    const detail = screen.getByRole('region', { name: 'Selected patient' });
    expect(detail).toHaveTextContent('New Synthetic Patient');
    expect(detail).toHaveTextContent('MRN-2001');
    expect(screen.getByRole('status')).toHaveTextContent(/registered/i);
  });

  it('saves an edit from the detail view and shows a visible confirmation', async () => {
    const user = userEvent.setup();
    const UPDATED = { ...PATIENTS[0], phone: '+20 100 000 0099' };
    fetchMock.mockImplementation((path, options = {}) => {
      if (options.method === 'PUT') return Promise.resolve(jsonResponse(UPDATED));
      return Promise.resolve(jsonResponse(PATIENTS));
    });
    renderPatients({ roles: ['RECEPTIONIST'] });
    const list = await waitForPatientList();
    await user.click(within(list).getAllByRole('button')[0]);

    await user.click(screen.getByRole('button', { name: 'Edit record' }));
    const form = screen.getByRole('region', { name: 'Patient form' });
    await user.clear(within(form).getByLabelText('Phone'));
    await user.type(within(form).getByLabelText('Phone'), '+20 100 000 0099');
    await user.click(within(form).getByRole('button', { name: 'Save changes' }));

    const put = fetchMock.mock.calls.find(([, options]) => options.method === 'PUT');
    expect(put[0]).toBe('/api/patients/11111111-1111-4111-8111-111111111111');
    expect(JSON.parse(put[1].body)).toEqual({
      fullName: 'Amal Hassan',
      phone: '+20 100 000 0099',
      email: 'amal.hassan@example.com',
      address: '1 Example Street, Test City',
    });

    const detail = screen.getByRole('region', { name: 'Selected patient' });
    expect(detail).toHaveTextContent('+20 100 000 0099');
    expect(screen.getByRole('status')).toHaveTextContent(/saved/i);
  });

  it('refetches the branch-scoped list under the new context-bound token after a successful context switch', async () => {
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
    const BRANCH_B_PATIENTS = [{
      id: '44444444-4444-4444-8444-444444444444',
      medicalRecordNumber: 'MRN-4001',
      fullName: 'Sara Wanis',
      dateOfBirth: '1992-02-02',
      sex: 'female',
      phone: '',
      email: '',
      nationalId: '',
      address: '',
      active: true,
    }];
    fetchMock.mockImplementation((path, options = {}) => {
      if (options.headers.Authorization === 'Bearer branch-b-token') {
        return Promise.resolve(jsonResponse(BRANCH_B_PATIENTS));
      }
      return Promise.resolve(jsonResponse(PATIENTS));
    });

    const view = render(<PatientsPage session={sessionA} onSessionExpired={onSessionExpired} />);
    const listA = await waitForPatientList();
    expect(within(listA).getAllByRole('button')).toHaveLength(2);

    // The app swapped in the complete switched session (new context-bound
    // token, new branch). The screen stays the single owner of its list and
    // simply reloads it — no duplicated fetching layer.
    view.rerender(<PatientsPage session={sessionB} onSessionExpired={onSessionExpired} />);

    const listB = await waitForPatientList();
    expect(within(listB).getAllByRole('button')).toHaveLength(1);
    expect(within(listB).getByText('Sara Wanis')).toBeInTheDocument();
    const calls = fetchMock.mock.calls.filter(([path]) => path === '/api/patients');
    expect(calls).toHaveLength(2);
    expect(calls[0][1].headers.Authorization).toBe('Bearer branch-a-token');
    expect(calls[1][1].headers.Authorization).toBe('Bearer branch-b-token');
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
});
