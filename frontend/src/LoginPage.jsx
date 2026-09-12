import React, { useState } from 'react';
import { authenticate } from './api.js';

export default function LoginPage({ onLogin }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const session = await authenticate(username.trim(), password);
      onLogin(session);
    } catch (err) {
      setError(err && err.message ? err.message : 'Invalid username or password.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-page">
      <main className="login-card" aria-labelledby="login-title">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">M</span>
          <div>
            <h1 id="login-title">MediCore</h1>
            <p className="brand-sub">Hospital Management</p>
          </div>
        </div>

        <h2>Sign in to your workspace</h2>
        <p className="login-hint">
          Use the account provided by your administrator. You start in the acting
          role and branch the server selects for you; you can switch context from
          the sidebar.
        </p>

        <form onSubmit={submit} noValidate={false}>
          <div className="field">
            <label htmlFor="login-username">Username</label>
            <input
              id="login-username"
              name="username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoFocus
              required
              disabled={busy}
            />
          </div>

          <div className="field">
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              name="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
              disabled={busy}
            />
          </div>

          {error && (
            <p className="form-error" role="alert" aria-live="assertive">
              {error}
            </p>
          )}

          <button type="submit" className="primary" disabled={busy}>
            {busy ? 'Signing in…' : 'Log in'}
          </button>
        </form>
      </main>
      <p className="login-footnote">Training build — not for clinical use.</p>
    </div>
  );
}
