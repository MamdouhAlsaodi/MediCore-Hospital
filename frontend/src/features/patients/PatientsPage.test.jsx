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

function renderPatients() {
  const onSessionExpired = vi.fn();
  const view = render(
    <PatientsPage
      session={{ token: 'synthetic-token', username: 'testuser', roles: ['DOCTOR'] }}
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

  it('selects a patient via native button keyboard activation and shows a bounded read-only summary', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(PATIENTS)));
    renderPatients();
    const list = await waitForPatientList();
    const rows = within(list).getAllByRole('button');

    rows[0].focus();
    await user.keyboard('{Enter}');

    const summary = screen.getByRole('region', { name: 'Selected patient' });
    expect(summary).toHaveTextContent('Amal Hassan');
    expect(summary).toHaveTextContent('MRN-1001');
    expect(summary).toHaveTextContent('1990-05-14');
    expect(within(summary).queryByText('National ID')).not.toBeInTheDocument();
    expect(summary).not.toHaveTextContent(PATIENTS[0].nationalId);
    expect(within(summary).getByText('Active')).toBeInTheDocument();
    expect(summary).toHaveTextContent(/read-only/i);
    expect(within(summary).queryByRole('button')).not.toBeInTheDocument();

    await user.click(rows[1]);
    expect(summary).toHaveTextContent('Omar Diab');
    expect(within(summary).getByText('Inactive')).toBeInTheDocument();
    expect(summary).toHaveTextContent('—');
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
});
