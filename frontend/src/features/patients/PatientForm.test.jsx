import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PatientForm from './PatientForm.jsx';

const PATIENT_ID = '44444444-4444-4444-8444-444444444444';

const EDIT_PATIENT = {
  id: PATIENT_ID,
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

const CREATED = {
  id: '99999999-9999-4999-8999-999999999999',
  medicalRecordNumber: 'MRN-2001',
  fullName: 'Amal Hassan',
  dateOfBirth: '1990-05-14',
  sex: 'female',
  phone: '+20 100 000 0002',
  email: 'amal.hassan@example.com',
  nationalId: 'NID-SYNTHETIC-002',
  address: '2 Example Street, Test City',
  active: true,
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderForm(overrides = {}) {
  const props = {
    session: { token: 'synthetic-token', username: 'testuser', roles: ['RECEPTIONIST'] },
    mode: 'create',
    patient: null,
    onSuccess: vi.fn(),
    onCancel: vi.fn(),
    onSessionExpired: vi.fn(),
    ...overrides,
  };
  const view = render(<PatientForm {...props} />);
  return { props, ...view };
}

async function fillRequiredCreateFields(user, mrn = 'MRN-2001', fullName = 'Amal Hassan') {
  await user.type(screen.getByLabelText('Medical record number'), mrn);
  await user.type(screen.getByLabelText('Full name'), fullName);
}

describe('PatientForm', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('blocks submit with inline errors when required create fields are blank and sends no request', async () => {
    const user = userEvent.setup();
    const { props } = renderForm();

    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    expect(screen.getByText('Medical record number is required.')).toBeInTheDocument();
    expect(screen.getByText('Full name is required.')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(props.onSuccess).not.toHaveBeenCalled();
  });

  it('rejects a malformed email address inline before any request is sent', async () => {
    const user = userEvent.setup();
    renderForm();

    await fillRequiredCreateFields(user);
    await user.type(screen.getByLabelText('Email'), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    expect(screen.getByText('Enter a valid email address.')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('sends the create payload to POST /api/patients and hands the saved patient to onSuccess', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse(CREATED, 201));
    const { props } = renderForm();

    await fillRequiredCreateFields(user);
    fireEvent.change(screen.getByLabelText('Date of birth'), { target: { value: '1990-05-14' } });
    await user.type(screen.getByLabelText('Sex'), 'female');
    await user.type(screen.getByLabelText('Phone'), '+20 100 000 0002');
    await user.type(screen.getByLabelText('Email'), 'amal.hassan@example.com');
    await user.type(screen.getByLabelText('National ID'), 'NID-SYNTHETIC-002');
    await user.type(screen.getByLabelText('Address'), '2 Example Street, Test City');
    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    await waitFor(() => expect(props.onSuccess).toHaveBeenCalledTimes(1));
    expect(props.onSuccess).toHaveBeenCalledWith(CREATED);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/patients');
    expect(options.method).toBe('POST');
    expect(options.headers.Authorization).toBe('Bearer synthetic-token');
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(options.body)).toEqual({
      medicalRecordNumber: 'MRN-2001',
      fullName: 'Amal Hassan',
      dateOfBirth: '1990-05-14',
      sex: 'female',
      phone: '+20 100 000 0002',
      email: 'amal.hassan@example.com',
      nationalId: 'NID-SYNTHETIC-002',
      address: '2 Example Street, Test City',
    });
  });

  it('shows a duplicate-record server error (409) inline and keeps every entered value', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse({ error: 'duplicate' }, 409));
    const { props } = renderForm();

    await fillRequiredCreateFields(user, 'MRN-1001', 'Amal Hassan');
    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/failed \(409\)/i);
    expect(screen.getByLabelText('Medical record number')).toHaveValue('MRN-1001');
    expect(screen.getByLabelText('Full name')).toHaveValue('Amal Hassan');
    expect(props.onSuccess).not.toHaveBeenCalled();
  });

  it('displays server validation errors (400) inline without losing form data', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse({ error: 'validation' }, 400));
    const { props } = renderForm();

    await fillRequiredCreateFields(user);
    await user.type(screen.getByLabelText('Phone'), '+20 100 000 0002');
    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/failed \(400\)/i);
    expect(screen.getByLabelText('Medical record number')).toHaveValue('MRN-2001');
    expect(screen.getByLabelText('Full name')).toHaveValue('Amal Hassan');
    expect(screen.getByLabelText('Phone')).toHaveValue('+20 100 000 0002');
    expect(props.onSuccess).not.toHaveBeenCalled();
  });

  it('disables the submit button while the create request is pending', async () => {
    let resolveRequest;
    fetchMock.mockReturnValueOnce(
      new Promise((resolve) => { resolveRequest = resolve; })
    );
    const user = userEvent.setup();
    const { props } = renderForm();

    await fillRequiredCreateFields(user);
    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    const saving = screen.getByRole('button', { name: /saving/i });
    expect(saving).toBeDisabled();

    resolveRequest(jsonResponse(CREATED, 201));
    await waitFor(() => expect(props.onSuccess).toHaveBeenCalledTimes(1));
  });

  it('cancel discards the draft without any request and a fresh form starts empty', async () => {
    const user = userEvent.setup();
    const first = renderForm();

    await user.type(screen.getByLabelText('Full name'), 'Half Typed Draft');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(first.props.onCancel).toHaveBeenCalledTimes(1);
    expect(fetchMock).not.toHaveBeenCalled();
    first.unmount();

    renderForm();
    expect(screen.getByLabelText('Full name')).toHaveValue('');
    expect(screen.getByLabelText('Medical record number')).toHaveValue('');
  });

  it('invokes the session-expiry callback on 401 and raises no local error on top of it', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse({ error: 'expired' }, 401));
    const { props } = renderForm();

    await fillRequiredCreateFields(user);
    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    await waitFor(() => expect(props.onSessionExpired).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(props.onSuccess).not.toHaveBeenCalled();
  });

  it('shows a visible permission denial alert on 403', async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(jsonResponse({ error: 'forbidden' }, 403));
    const { props } = renderForm();

    await fillRequiredCreateFields(user);
    await user.click(screen.getByRole('button', { name: 'Save patient' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/permission/i);
    expect(props.onSuccess).not.toHaveBeenCalled();
  });

  it('edits via PUT /api/patients/{id} with only the update-contract fields and keeps the MRN read-only', async () => {
    const user = userEvent.setup();
    const UPDATED = { ...EDIT_PATIENT, phone: '+20 100 000 0099' };
    fetchMock.mockResolvedValueOnce(jsonResponse(UPDATED));
    const { props } = renderForm({ mode: 'edit', patient: EDIT_PATIENT });

    const mrn = screen.getByLabelText('Medical record number');
    expect(mrn).toBeDisabled();
    expect(mrn).toHaveValue('MRN-1001');
    expect(screen.getByLabelText('Full name')).toHaveValue('Amal Hassan');
    expect(screen.queryByLabelText('Date of birth')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('National ID')).not.toBeInTheDocument();

    await user.clear(screen.getByLabelText('Phone'));
    await user.type(screen.getByLabelText('Phone'), '+20 100 000 0099');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(props.onSuccess).toHaveBeenCalledTimes(1));
    expect(props.onSuccess).toHaveBeenCalledWith(UPDATED);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe(`/api/patients/${PATIENT_ID}`);
    expect(options.method).toBe('PUT');
    expect(JSON.parse(options.body)).toEqual({
      fullName: 'Amal Hassan',
      phone: '+20 100 000 0099',
      email: 'amal.hassan@example.com',
      address: '1 Example Street, Test City',
    });
  });

  it('renders a read-only form view with no submit for a role that cannot edit', async () => {
    const user = userEvent.setup();
    const { props } = renderForm({
      mode: 'view',
      patient: EDIT_PATIENT,
      session: { token: 'synthetic-token', username: 'testuser', roles: ['DOCTOR'] },
    });

    expect(screen.getByLabelText('Medical record number')).toBeDisabled();
    expect(screen.getByLabelText('Full name')).toBeDisabled();
    expect(screen.queryByRole('button', { name: /save/i })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /patient record form/i })).toHaveTextContent(/read-only/i);
    expect(screen.getByText(/inspect this record form but not change it/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Back to detail' }));
    expect(props.onCancel).toHaveBeenCalledTimes(1);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('renders edit mode as read-only when the session role lacks edit permission', () => {
    renderForm({
      mode: 'edit',
      patient: EDIT_PATIENT,
      session: { token: 'synthetic-token', username: 'testuser', roles: ['DOCTOR'] },
    });

    expect(screen.getByLabelText('Full name')).toBeDisabled();
    expect(screen.queryByRole('button', { name: /save/i })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('never sends a request while the form is read-only even if submit is forced', async () => {
    const user = userEvent.setup();
    const { props } = renderForm({
      mode: 'view',
      patient: EDIT_PATIENT,
      session: { token: 'synthetic-token', username: 'testuser', roles: ['NURSE'] },
    });

    const form = document.querySelector('form');
    fireEvent.submit(form);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(props.onSuccess).not.toHaveBeenCalled();
  });
});
