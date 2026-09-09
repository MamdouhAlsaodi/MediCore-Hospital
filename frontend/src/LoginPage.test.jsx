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
      jsonResponse({
        accessToken: 'synthetic-access-token',
        username: 'receptionist',
        roles: ['RECEPTIONIST'],
      })
    );

    await waitFor(() => expect(onLogin).toHaveBeenCalledTimes(1));
    expect(onLogin).toHaveBeenCalledWith({
      token: 'synthetic-access-token',
      username: 'receptionist',
      roles: ['RECEPTIONIST'],
    });
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
