import React, { useEffect, useState } from 'react';
import { apiFetch, ApiError } from './api.js';

// Dashboard screen only: statistics over GET /api/dashboard (any authenticated
// role, matching SecurityConfig). Layout chrome and navigation live in AppShell.
export default function DashboardPage({ session, onSessionExpired }) {
  const [stats, setStats] = useState(null);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    let active = true;
    apiFetch('/api/dashboard', { token: session.token, onUnauthorized: onSessionExpired })
      .then((data) => { if (active) { setStats(data); setLoadError(''); } })
      .catch((err) => {
        if (!active) return;
        setLoadError(err instanceof ApiError ? err.message : 'The dashboard could not be loaded.');
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired]);

  return (
    <>
      {loadError && (
        <p className="notice error" role="alert" aria-live="assertive">{loadError}</p>
      )}

      <section className="cards" aria-label="Key statistics">
        {stats
          ? Object.entries(stats).map(([label, value]) => (
              <article key={label}>
                <small>{label}</small>
                <strong>{value}</strong>
              </article>
            ))
          : !loadError && <article><small>Status</small><strong>Loading…</strong></article>}
      </section>
    </>
  );
}
