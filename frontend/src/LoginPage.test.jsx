import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LoginPage from './LoginPage.jsx';

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

// Complete server-issued login response (docs/plan3.md Task 3 shape).
const SERVER_ASSIGNMENT = {
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

function serverLoginPayload(overrides = {}) {
  return {
    accessToken: 'synthetic-access-token',
    tokenType: 'Bearer',
    username: 'receptionist',
    roles: ['RECEPTIONIST'],
    assignments: [SERVER_ASSIGNMENT],
    actingContext: {
      username: 'receptionist',
      assignmentId: SERVER_ASSIGNMENT.id,
      role: 'RECEPTIONIST',
      scope: 'BRANCH',
      organizationId: SERVER_ASSIGNMENT.organizationId,
      branchId: SERVER_ASSIGNMENT.branchId,
      departmentId: null,
    },
    ...overrides,
  };
}

function stubPendingFetch() {
  let resolveLogin;
  vi.stubGlobal(
    'fetch',
    vi.fn(
      () =>
        new Promise((resolve) => {
          resolveLogin = resolve;
        })
    )
  );
  return (response) => resolveLogin(response);
}

describe('LoginPage', () => {
  it('labels the username and password controls accessibly and names the submit button "Log in"', () => {
    render(<LoginPage onLogin={vi.fn()} />);

    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Log in' })).toBeInTheDocument();
  });

  it('submits on Enter in the password field, shows a disabled loading state, then passes the session to onLogin', async () => {
    const user = userEvent.setup();
    const onLogin = vi.fn();
    const resolveLogin = stubPendingFetch();
    render(<LoginPage onLogin={onLogin} />);

    await user.type(screen.getByLabelText('Username'), 'receptionist');
    await user.type(screen.getByLabelText('Password'), 'synthetic-password');
    await user.type(screen.getByLabelText('Password'), '{Enter}');

    expect(screen.getByRole('button', { name: /signing in/i })).toBeDisabled();

    resolveLogin(
      jsonResponse(serverLoginPayload())
    );

    await waitFor(() => expect(onLogin).toHaveBeenCalledTimes(1));
    expect(onLogin).toHaveBeenCalledWith({
      token: 'synthetic-access-token',
      username: 'receptionist',
      roles: ['RECEPTIONIST'],
      assignments: [SERVER_ASSIGNMENT],
      actingContext: serverLoginPayload().actingContext,
    });
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
  });

  it('tells the user the workspace opens in the server-selected acting context', () => {
    render(<LoginPage onLogin={vi.fn()} />);

    expect(screen.getByText(/acting role and branch the server selects/i)).toBeInTheDocument();
  });

  it('rejects a login response without the complete acting-context structure and stays on the form', async () => {
    const user = userEvent.setup();
    const onLogin = vi.fn();
    vi.stubGlobal(
      'fetch',
      vi.fn(() => jsonResponse(serverLoginPayload({ actingContext: undefined, assignments: undefined })))
    );
    render(<LoginPage onLogin={onLogin} />);

    await user.type(screen.getByLabelText('Username'), 'receptionist');
    await user.type(screen.getByLabelText('Password'), 'synthetic-password');
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The server returned an unexpected session response.'
    );
    expect(onLogin).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
  });

  it('shows the visible alert text and re-enables the submit button when the login request is rejected', async () => {
    const user = userEvent.setup();
    const onLogin = vi.fn();
    vi.stubGlobal(
      'fetch',
      vi.fn(() => jsonResponse({ error: 'denied' }, 401))
    );
    render(<LoginPage onLogin={onLogin} />);

    await user.type(screen.getByLabelText('Username'), 'rejected-user');
    await user.type(screen.getByLabelText('Password'), 'synthetic-password');
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid username or password.'
    );
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
    expect(onLogin).not.toHaveBeenCalled();
  });
});
