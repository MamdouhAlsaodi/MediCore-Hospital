import React, { useEffect, useState } from 'react';
import { apiFetch, ApiError } from './api.js';

// Human-readable labels for the keys the Task 5 backend contract guarantees
// (docs/plan2.md Task 5). Response values stay the single source of truth;
// unknown keys keep the generic fallback section.
const CURRENT_ACTIVITY = {
  openAdmissions: 'Open admissions',
  activeEmergencyVisits: 'Active emergency visits',
};
const TOTALS = {
  patients: 'Patients',
  appointments: 'Appointments',
  admissions: 'Admissions',
  emergencyVisits: 'Emergency visits',
  invoices: 'Invoices',
};
const INVOICE_STATUSES = {
  invoicesDraft: 'Draft',
  invoicesIssued: 'Issued',
  invoicesPaid: 'Paid',
  invoicesVoid: 'Void',
};
const KNOWN_KEYS = new Set(Object.keys({ ...CURRENT_ACTIVITY, ...TOTALS, ...INVOICE_STATUSES }));

function StatCard({ label, value }) {
  // '—' marks a contract key this response did not carry; a zero is only
  // ever rendered when the server actually reported one.
  return (
    <article>
      <small>{label}</small>
      <strong>{value === undefined ? '—' : value}</strong>
    </article>
  );
}

function StatGroup({ name, entries }) {
  return (
    <section className="dashboard-group" aria-label={name}>
      <h2>{name}</h2>
      <div className="cards">
        {entries.map(([label, value]) => <StatCard key={label} label={label} value={value} />)}
      </div>
    </section>
  );
}

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
        // apiFetch already invoked onSessionExpired for a 401; showing a
        // local error on top would be misleading, so only non-401 failures
        // render here (same convention as the other screens).
        if (err instanceof ApiError && err.status === 401) return;
        setLoadError(err instanceof ApiError ? err.message : 'The dashboard could not be loaded.');
      });
    return () => { active = false; };
  }, [session.token, onSessionExpired]);

  const groupEntries = (labels) =>
    Object.entries(labels).map(([key, label]) => [label, stats ? stats[key] : undefined]);
  const unknownEntries = stats
    ? Object.entries(stats).filter(([key]) => !KNOWN_KEYS.has(key))
    : [];

  return (
    <>
      {loadError && (
        <p className="notice error" role="alert" aria-live="assertive">{loadError}</p>
      )}

      {!stats && !loadError && <p className="notice">Loading…</p>}

      {stats && <StatGroup name="Current activity" entries={groupEntries(CURRENT_ACTIVITY)} />}
      {stats && <StatGroup name="Totals" entries={groupEntries(TOTALS)} />}
      {stats && <StatGroup name="Invoices by status" entries={groupEntries(INVOICE_STATUSES)} />}

      {stats && unknownEntries.length > 0 && (
        <StatGroup
          name="Other reported keys"
          entries={unknownEntries.map(([key, value]) => [key, value])}
        />
      )}
    </>
  );
}
